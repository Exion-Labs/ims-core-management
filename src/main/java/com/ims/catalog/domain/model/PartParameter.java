package com.ims.catalog.domain.model;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Typed parameter value assigned to a Part, storing both human-readable value_text (e.g. "4.7 uF")
 * and SI-normalized value_numeric (e.g. 4.7E-6) for B-tree range queries.
 */
@Entity
@Table(name = "part_parameters")
@Audited
public class PartParameter extends VersionedTenantEntity {

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "value_text", nullable = false, length = 500)
    private String valueText;

    @Column(name = "value_numeric")
    private Double valueNumeric;

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public String getValueText() {
        return valueText;
    }

    public void setValueText(String valueText) {
        this.valueText = valueText;
    }

    public Double getValueNumeric() {
        return valueNumeric;
    }

    public void setValueNumeric(Double valueNumeric) {
        this.valueNumeric = valueNumeric;
    }
}
