package com.ims.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SupportSessionRepository extends JpaRepository<SupportSession, UUID> {
    List<SupportSession> findByOrderByStartedAtDesc();
}
