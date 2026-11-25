package com.ims.identity.infrastructure.persistence;

import com.ims.identity.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, UUID> {
    List<UserRoleAssignment> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
