package com.ims.identity.api;

import com.ims.common.audit.AuditEvent;
import com.ims.common.sequence.ReferenceSequence;
import com.ims.config.security.TenantUserPrincipal;
import com.ims.entitlement.spi.Feature;
import com.ims.entitlement.spi.RequiresFeature;
import com.ims.identity.application.TenantManagementService;
import com.ims.identity.domain.model.ApiToken;
import com.ims.identity.domain.model.Role;
import com.ims.identity.domain.model.Tenant;
import com.ims.identity.domain.model.TenantSetting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TenantSettingsController {

    public record OrgProfileUpdateDto(String name, String baseCurrency, String timezone, String logoUrl) {}
    public record UserInviteDto(@NotBlank @Email String email, @NotBlank String fullName, List<UUID> roleIds) {}
    public record UserRoleUpdateDto(@NotNull List<UUID> roleIds) {}
    public record RoleUpsertDto(@NotBlank String name, String description, @NotNull List<String> permissions) {}
    public record ApiTokenCreateDto(@NotBlank String name, List<String> scopes, Instant expiresAt) {}
    public record SettingUpsertDto(@NotBlank String settingKey, @NotNull String settingValue, String valueType) {}
    public record SequenceUpdateDto(@NotBlank String prefix, int padding, long nextValue) {}

    private final TenantManagementService managementService;

    public TenantSettingsController(TenantManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping("/me")
    public TenantManagementService.MeResponse getMe(@AuthenticationPrincipal TenantUserPrincipal principal) {
        return managementService.getMe(principal);
    }

    @PatchMapping("/settings/organization")
    @PreAuthorize("hasAuthority('ADMIN:UPDATE')")
    public Tenant updateOrg(@Valid @RequestBody OrgProfileUpdateDto dto) {
        return managementService.updateOrganization(dto.name(), dto.baseCurrency(), dto.timezone(), dto.logoUrl());
    }

    @GetMapping("/settings/users")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public List<TenantManagementService.UserWithRolesView> listUsers() {
        return managementService.listUsers();
    }

    @PostMapping("/settings/users/invite")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN:CREATE')")
    public TenantManagementService.InviteUserResult inviteUser(@Valid @RequestBody UserInviteDto dto) {
        return managementService.inviteUser(new TenantManagementService.InviteUserCommand(
                dto.email(), dto.fullName(), dto.roleIds()
        ));
    }

    @PutMapping("/settings/users/{userId}/roles")
    @PreAuthorize("hasAuthority('ADMIN:UPDATE')")
    public TenantManagementService.UserWithRolesView updateUserRoles(
            @PathVariable UUID userId,
            @Valid @RequestBody UserRoleUpdateDto dto
    ) {
        return managementService.updateUserRoles(userId, dto.roleIds());
    }

    @GetMapping("/settings/roles")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public List<Role> listRoles() {
        return managementService.listRoles();
    }

    @GetMapping("/settings/permissions")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public List<String> availablePermissions() {
        return managementService.getAvailablePermissionsForTenant();
    }

    @PostMapping("/settings/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN:CREATE')")
    public Role createRole(@Valid @RequestBody RoleUpsertDto dto) {
        return managementService.createRole(dto.name(), dto.description(), dto.permissions());
    }

    @PutMapping("/settings/roles/{roleId}")
    @PreAuthorize("hasAuthority('ADMIN:UPDATE')")
    public Role updateRole(@PathVariable UUID roleId, @Valid @RequestBody RoleUpsertDto dto) {
        return managementService.updateRole(roleId, dto.name(), dto.description(), dto.permissions());
    }

    @DeleteMapping("/settings/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ADMIN:DELETE')")
    public void deleteRole(@PathVariable UUID roleId) {
        managementService.deleteRole(roleId);
    }

    @GetMapping("/settings/api-tokens")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    @RequiresFeature(Feature.API_ACCESS)
    public List<ApiToken> listApiTokens() {
        return managementService.listApiTokens();
    }

    @PostMapping("/settings/api-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN:CREATE')")
    @RequiresFeature(Feature.API_ACCESS)
    public TenantManagementService.CreatedApiTokenResponse createApiToken(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @Valid @RequestBody ApiTokenCreateDto dto
    ) {
        return managementService.createApiToken(principal.userId(), new TenantManagementService.CreateApiTokenCommand(
                dto.name(), dto.scopes(), dto.expiresAt()
        ));
    }

    @DeleteMapping("/settings/api-tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ADMIN:DELETE')")
    @RequiresFeature(Feature.API_ACCESS)
    public void revokeApiToken(@PathVariable UUID tokenId) {
        managementService.revokeApiToken(tokenId);
    }

    @GetMapping("/settings/kv")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public List<TenantSetting> listKvSettings() {
        return managementService.listSettings();
    }

    @PutMapping("/settings/kv")
    @PreAuthorize("hasAuthority('ADMIN:UPDATE')")
    public TenantSetting upsertKvSetting(@Valid @RequestBody SettingUpsertDto dto) {
        return managementService.upsertSetting(dto.settingKey(), dto.settingValue(), dto.valueType());
    }

    @GetMapping("/settings/sequences")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public List<ReferenceSequence> listSequences() {
        return managementService.listReferenceSequences();
    }

    @PutMapping("/settings/sequences/{sequenceKey}")
    @PreAuthorize("hasAuthority('ADMIN:UPDATE')")
    public ReferenceSequence updateSequence(
            @PathVariable String sequenceKey,
            @Valid @RequestBody SequenceUpdateDto dto
    ) {
        return managementService.updateReferenceSequence(sequenceKey, dto.prefix(), dto.padding(), dto.nextValue());
    }

    @GetMapping("/settings/audit-events")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    public Page<AuditEvent> searchAuditEvents(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return managementService.searchAuditEvents(
                entityType, entityId, actorId, action, from, to, PageRequest.of(page, Math.min(100, size))
        );
    }

    @GetMapping("/settings/audit-events/export")
    @PreAuthorize("hasAuthority('ADMIN:VIEW')")
    @RequiresFeature(Feature.IMPORT_EXPORT)
    public ResponseEntity<byte[]> exportAuditEventsCsv(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        byte[] csv = managementService.exportAuditEventsCsv(entityType, entityId, actorId, action, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-events.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
