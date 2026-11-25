package com.ims.catalog.infrastructure.persistence;

import com.ims.catalog.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BomItemRepository extends JpaRepository<BomItem, UUID> {

    List<BomItem> findByPartIdOrderByCreatedAtAsc(UUID partId);

    List<BomItem> findBySubPartIdOrderByCreatedAtAsc(UUID subPartId);

    Optional<BomItem> findByPartIdAndSubPartId(UUID partId, UUID subPartId);

    boolean existsByPartIdAndSubPartId(UUID partId, UUID subPartId);
}
