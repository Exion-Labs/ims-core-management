package com.ims.stock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PartTestTemplateRepository extends JpaRepository<PartTestTemplate, UUID> {

    List<PartTestTemplate> findByPartIdOrderByTestNameAsc(UUID partId);

    boolean existsByPartIdAndTestNameIgnoreCase(UUID partId, String testName);
}
