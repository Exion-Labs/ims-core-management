package com.ims.part;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Reusable parameter definition with physical units (e.g., F, Ohm, V, A, mm), choices, or boolean checkbox.
 */
@Entity
@Table(name = "parameter_templates")
@Audited
public class ParameterTemplate extends VersionedTenantEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "units", length = 50)
    private String units;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "choices", length = 1000)
    private String choices;

    @Column(name = "checkbox", nullable = false)
    private boolean checkbox = false;

    @Column(name = "default_category_id")
    private UUID defaultCategoryId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUnits() {
        return units;
    }

    public void setUnits(String units) {
        this.units = units;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getChoices() {
        return choices;
    }

    public void setChoices(String choices) {
        this.choices = choices;
    }

    public boolean isCheckbox() {
        return checkbox;
    }

    public void setCheckbox(boolean checkbox) {
        this.checkbox = checkbox;
    }

    public UUID getDefaultCategoryId() {
        return defaultCategoryId;
    }

    public void setDefaultCategoryId(UUID defaultCategoryId) {
        this.defaultCategoryId = defaultCategoryId;
    }
}
