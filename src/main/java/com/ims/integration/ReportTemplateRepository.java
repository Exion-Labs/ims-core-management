package com.ims.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, UUID> {
    List<ReportTemplate> findByOrderByNameAsc();
    List<ReportTemplate> findByEntityTypeOrderByNameAsc(String entityType);
}
