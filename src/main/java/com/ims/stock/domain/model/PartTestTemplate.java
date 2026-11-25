package com.ims.stock.domain.model;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Quality control inspection test template defined for a Part.
 */
@Entity
@Table(name = "part_test_templates")
@Audited
public class PartTestTemplate extends VersionedTenantEntity {

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "test_name", nullable = false, length = 150)
    private String testName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "required", nullable = false)
    private boolean required = true;

    @Column(name = "requires_value", nullable = false)
    private boolean requiresValue = false;

    @Column(name = "requires_attachment", nullable = false)
    private boolean requiresAttachment = false;

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public boolean isRequiresValue() {
        return requiresValue;
    }

    public void setRequiresValue(boolean requiresValue) {
        this.requiresValue = requiresValue;
    }

    public boolean isRequiresAttachment() {
        return requiresAttachment;
    }

    public void setRequiresAttachment(boolean requiresAttachment) {
        this.requiresAttachment = requiresAttachment;
    }
}
