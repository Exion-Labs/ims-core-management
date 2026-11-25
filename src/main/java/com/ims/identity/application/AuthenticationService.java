package com.ims.identity.application;

import com.ims.common.audit.AuditService;
import com.ims.config.security.JwtTokenService;
import com.ims.config.tenant.TenantContext;
import com.ims.identity.domain.model.RefreshToken;
import com.ims.identity.domain.model.Tenant;
import com.ims.identity.domain.model.UserAccount;
import com.ims.identity.domain.model.UserRoleAssignment;
import com.ims.identity.infrastructure.persistence.RefreshTokenRepository;
import com.ims.identity.infrastructure.persistence.RoleRepository;
import com.ims.identity.infrastructure.persistence.TenantRepository;
import com.ims.identity.infrastructure.persistence.UserAccountRepository;
import com.ims.identity.infrastructure.persistence.UserRoleAssignmentRepository;
import com.ims.identity.spi.TenantProvisioningPort;
import com.ims.shared.exception.ForbiddenException;
import com.ims.shared.exception.ResourceNotFoundException;
import com.ims.shared.exception.UnauthorizedException;
import com.ims.shared.exception.ValidationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthenticationService {

    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final UserRoleAssignmentRepository userRoleAssignmentRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public AuthenticationService(
            TenantRepository tenantRepository,
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            UserRoleAssignmentRepository userRoleAssignmentRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuditService auditService,
            JwtTokenService jwtTokenService,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate
    ) {
        this.tenantRepository = tenantRepository;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.userRoleAssignmentRepository = userRoleAssignmentRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditService = auditService;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
    }

    public TenantProvisioningPort.AuthTokensResponse login(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password."));
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new UnauthorizedException("Tenant not found."));

        if (!Tenant.Status.ACTIVE.name().equals(tenant.getStatus())) {
            throw new ForbiddenException("TENANT_SUSPENDED", "TENANT_SUSPENDED");
        }
        if (!UserAccount.Status.ACTIVE.name().equals(user.getStatus())) {
            throw new ForbiddenException("User account is not active.");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            auditService.recordInNewTransactionForTenant(
                    tenant.getId(),
                    "UserAccount",
                    user.getId(),
                    "LOGIN_FAILED",
                    Map.of("email", user.getEmail()),
                    "Invalid password"
            );
            throw new UnauthorizedException("Invalid email or password.");
        }

        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userAccountRepository.save(user);

        auditService.recordInNewTransactionForTenant(
                tenant.getId(),
                "UserAccount",
                user.getId(),
                "LOGIN",
                Map.of("email", user.getEmail()),
                null
        );

        return issueTokensForUser(user, tenant);
    }

    public TenantProvisioningPort.AuthTokensResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new UnauthorizedException("Refresh token is required.");
        }
        String hash = JwtTokenService.sha256Hex(rawRefreshToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token."));

        if (existing.isRevoked() || existing.getExpiresAt().isBefore(Instant.now())) {
            for (RefreshToken token : refreshTokenRepository.findByUserIdAndRevokedFalse(existing.getUserId())) {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            }
            throw new UnauthorizedException("Refresh token is expired or already used.");
        }

        existing.setRevoked(true);
        refreshTokenRepository.save(existing);

        UserAccount user = userAccountRepository.findById(existing.getUserId())
                .orElseThrow(() -> new UnauthorizedException("User not found."));
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new UnauthorizedException("Tenant not found."));

        if (!Tenant.Status.ACTIVE.name().equals(tenant.getStatus())) {
            throw new ForbiddenException("TENANT_SUSPENDED", "TENANT_SUSPENDED");
        }
        return issueTokensForUser(user, tenant);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String hash = JwtTokenService.sha256Hex(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
            auditService.recordInNewTransactionForTenant(
                    rt.getTenantId(),
                    "UserAccount",
                    rt.getUserId(),
                    "LOGOUT",
                    Map.of(),
                    null
            );
        });
    }

    public TenantProvisioningPort.AuthTokensResponse acceptInvite(String inviteToken, String fullName, String password) {
        UserAccount user = userAccountRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or expired invitation token."));
        Tenant tenant = tenantRepository.findById(user.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found."));

        user.setFullName(fullName.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(UserAccount.Status.ACTIVE.name());
        user.setInviteToken(null);
        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userAccountRepository.save(user);

        return issueTokensForUser(user, tenant);
    }

    @Transactional
    public String requestPasswordReset(String email) {
        Optional<UserAccount> userOpt = userAccountRepository.findByEmailIgnoreCase(email.trim());
        if (userOpt.isEmpty()) {
            return null;
        }
        UserAccount user = userOpt.get();
        String token = JwtTokenService.generateRandomToken(24);
        user.setResetToken(token);
        user.setResetExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        userAccountRepository.save(user);
        return token;
    }

    @Transactional
    public void resetPassword(String resetToken, String newPassword) {
        UserAccount user = userAccountRepository.findByResetToken(resetToken)
                .orElseThrow(() -> new ValidationException("Invalid password reset token."));
        if (user.getResetExpiresAt() == null || user.getResetExpiresAt().isBefore(Instant.now())) {
            throw new ValidationException("Password reset token has expired.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setResetToken(null);
        user.setResetExpiresAt(null);
        user.setUpdatedAt(Instant.now());
        userAccountRepository.save(user);

        auditService.recordInNewTransactionForTenant(
                user.getTenantId(),
                "UserAccount",
                user.getId(),
                "PASSWORD_RESET",
                Map.of("email", user.getEmail()),
                null
        );
    }

    public Set<String> resolveEffectivePermissions(UUID tenantId, UUID userId) {
        TransactionTemplate newTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        newTx.setReadOnly(true);
        return TenantContext.callUncheckedAs(tenantId, () ->
                newTx.execute(status -> {
                    List<UserRoleAssignment> assignments = userRoleAssignmentRepository.findByUserId(userId);
                    Set<String> perms = new LinkedHashSet<>();
                    for (UserRoleAssignment a : assignments) {
                        roleRepository.findById(a.getRoleId()).ifPresent(role -> perms.addAll(role.getPermissions()));
                    }
                    return perms;
                })
        );
    }

    public TenantProvisioningPort.AuthTokensResponse issueTokensForUser(UserAccount user, Tenant tenant) {
        Set<String> permissions = resolveEffectivePermissions(tenant.getId(), user.getId());
        String accessToken = jwtTokenService.createTenantAccessToken(
                user.getId(),
                tenant.getId(),
                user.getEmail(),
                user.getFullName(),
                permissions
        );

        String rawRefreshToken = JwtTokenService.generateRandomToken(32);
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTenantId(tenant.getId());
        rt.setTokenHash(JwtTokenService.sha256Hex(rawRefreshToken));
        rt.setExpiresAt(Instant.now().plus(REFRESH_TOKEN_TTL));
        refreshTokenRepository.save(rt);

        return new TenantProvisioningPort.AuthTokensResponse(
                accessToken,
                rawRefreshToken,
                jwtTokenService.getAccessTokenTtlSeconds(),
                user.getId(),
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                user.getEmail(),
                user.getFullName(),
                permissions
        );
    }
}
