package com.ims.integration;

import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "report_templates")
public class ReportTemplate extends BaseTenantEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "template_html", nullable = false, columnDefinition = "text")
    private String templateHtml;

    @Column(name = "page_size", nullable = false, length = 32)
    private String pageSize = "A4";

    @Column(nullable = false)
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getTemplateHtml() { return templateHtml; }
    public void setTemplateHtml(String templateHtml) { this.templateHtml = templateHtml; }
    public String getPageSize() { return pageSize; }
    public void setPageSize(String pageSize) { this.pageSize = pageSize; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
