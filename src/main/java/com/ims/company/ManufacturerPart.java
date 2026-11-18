package com.ims.company;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "manufacturer_parts")
@Audited
public class ManufacturerPart extends VersionedTenantEntity {

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    @Column(name = "manufacturer_id", nullable = false)
    private UUID manufacturerId;

    @Column(name = "mpn", nullable = false, length = 120)
    private String mpn;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "datasheet_url", length = 500)
    private String datasheetUrl;

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }

    public UUID getManufacturerId() {
        return manufacturerId;
    }

    public void setManufacturerId(UUID manufacturerId) {
        this.manufacturerId = manufacturerId;
    }

    public String getMpn() {
        return mpn;
    }

    public void setMpn(String mpn) {
        this.mpn = mpn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDatasheetUrl() {
        return datasheetUrl;
    }

    public void setDatasheetUrl(String datasheetUrl) {
        this.datasheetUrl = datasheetUrl;
    }
}
