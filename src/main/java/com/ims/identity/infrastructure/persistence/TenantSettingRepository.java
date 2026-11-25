package com.ims.identity.infrastructure.persistence;

import com.ims.identity.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantSettingRepository extends JpaRepository<TenantSetting, UUID> {
    Optional<TenantSetting> findBySettingKey(String settingKey);
    List<TenantSetting> findAllByOrderBySettingKeyAsc();
}
