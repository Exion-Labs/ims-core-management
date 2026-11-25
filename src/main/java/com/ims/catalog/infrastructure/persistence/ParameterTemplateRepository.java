package com.ims.catalog.infrastructure.persistence;

import com.ims.catalog.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParameterTemplateRepository extends JpaRepository<ParameterTemplate, UUID> {

    List<ParameterTemplate> findAllByOrderByNameAsc();

    Optional<ParameterTemplate> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<ParameterTemplate> findByDefaultCategoryIdOrderByNameAsc(UUID defaultCategoryId);

    List<ParameterTemplate> findByDefaultCategoryIdInOrderByNameAsc(Collection<UUID> categoryIds);
}
