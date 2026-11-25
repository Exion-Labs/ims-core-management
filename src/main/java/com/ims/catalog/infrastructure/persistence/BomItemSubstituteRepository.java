package com.ims.catalog.infrastructure.persistence;

import com.ims.catalog.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BomItemSubstituteRepository extends JpaRepository<BomItemSubstitute, UUID> {

    List<BomItemSubstitute> findByBomItemIdOrderByCreatedAtAsc(UUID bomItemId);

    boolean existsByBomItemIdAndPartId(UUID bomItemId, UUID partId);
}
