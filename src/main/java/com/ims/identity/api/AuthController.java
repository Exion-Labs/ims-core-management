package com.ims.identity.api;

import com.ims.identity.application.AuthenticationService;
import com.ims.identity.spi.TenantProvisioningPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record TenantOnboardingDto(
            @NotBlank String tenantName,
            @NotBlank String tenantSlug,
            @NotBlank @Email String adminEmail,
            @NotBlank String adminFullName,
            @NotBlank String adminPassword,
            String baseCurrency,
            String timezone
    ) {}

    public record CredentialsDto(@NotBlank @Email String email, @NotBlank String password) {}
    public record TokenRotationDto(@NotBlank String refreshToken) {}
    public record InviteCompletionDto(@NotBlank String inviteToken, @NotBlank String fullName, @NotBlank String password) {}
    public record RecoveryInitDto(@NotBlank @Email String email) {}
    public record RecoveryFinishDto(@NotBlank String resetToken, @NotBlank String newPassword) {}

    private final TenantProvisioningPort tenantProvisioningPort;
    private final AuthenticationService authenticationService;

    public AuthController(
            TenantProvisioningPort tenantProvisioningPort,
            AuthenticationService authenticationService
    ) {
        this.tenantProvisioningPort = tenantProvisioningPort;
        this.authenticationService = authenticationService;
    }

    @PostMapping("/register-tenant")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantProvisioningPort.AuthTokensResponse onboard(@Valid @RequestBody TenantOnboardingDto body) {
        return tenantProvisioningPort.registerTenant(new TenantProvisioningPort.ProvisionTenantCommand(
                body.tenantName(), body.tenantSlug(), body.adminEmail(),
                body.adminFullName(), body.adminPassword(), body.baseCurrency(), body.timezone(), "TRIAL"
        ), false);
    }

    @PostMapping("/login")
    public TenantProvisioningPort.AuthTokensResponse signIn(@Valid @RequestBody CredentialsDto body) {
        return authenticationService.login(body.email(), body.password());
    }

    @PostMapping("/refresh")
    public TenantProvisioningPort.AuthTokensResponse rotate(@Valid @RequestBody TokenRotationDto body) {
        return authenticationService.refresh(body.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(@RequestBody(required = false) TokenRotationDto body) {
        if (body != null) {
            authenticationService.logout(body.refreshToken());
        }
    }

    @PostMapping("/accept-invite")
    public TenantProvisioningPort.AuthTokensResponse completeInvite(@Valid @RequestBody InviteCompletionDto body) {
        return authenticationService.acceptInvite(body.inviteToken(), body.fullName(), body.password());
    }

    @PostMapping("/forgot-password")
    public Map<String, String> initRecovery(@Valid @RequestBody RecoveryInitDto body) {
        authenticationService.requestPasswordReset(body.email());
        return Map.of("status", "RECOVERY_DISPATCHED");
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void finishRecovery(@Valid @RequestBody RecoveryFinishDto body) {
        authenticationService.resetPassword(body.resetToken(), body.newPassword());
    }
}
