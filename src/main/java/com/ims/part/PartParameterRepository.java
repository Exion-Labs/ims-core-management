package com.ims.part;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartParameterRepository extends JpaRepository<PartParameter, UUID> {

    List<PartParameter> findByPartIdOrderByCreatedAtAsc(UUID partId);

    Optional<PartParameter> findByPartIdAndTemplateId(UUID partId, UUID templateId);

    List<PartParameter> findByPartIdIn(Collection<UUID> partIds);
}
