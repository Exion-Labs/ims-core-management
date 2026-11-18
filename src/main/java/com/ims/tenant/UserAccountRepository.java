package com.ims.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    Optional<UserAccount> findByInviteToken(String inviteToken);
    Optional<UserAccount> findByResetToken(String resetToken);
    List<UserAccount> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);
    Optional<UserAccount> findByTenantIdAndId(UUID tenantId, UUID id);
    long countByTenantIdAndStatusNot(UUID tenantId, String status);
}
