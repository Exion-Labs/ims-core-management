package com.ims.collaboration.infrastructure.persistence;

import com.ims.collaboration.domain.model.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId);

    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM Attachment a WHERE a.status = 'READY'")
    long sumReadySizeBytes();
}
