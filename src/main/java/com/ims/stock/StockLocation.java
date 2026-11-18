package com.ims.stock;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import com.ims.config.hibernate.LtreeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.util.UUID;

/**
 * Hierarchical storage location (Warehouse -> Room -> Aisle -> Rack -> Bin) using PostgreSQL ltree.
 */
@Entity
@Table(name = "stock_locations")
@Audited
public class StockLocation extends VersionedTenantEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "parent_id")
    private UUID parentId;

    @Type(LtreeType.class)
    @Column(name = "path", nullable = false, columnDefinition = "ltree")
    private String path;

    @Column(name = "path_string", nullable = false, length = 1000)
    private String pathString;

    @Column(name = "location_type", nullable = false, length = 50)
    private String locationType = "BIN";

    @Column(name = "structural", nullable = false)
    private boolean structural = false;

    @Column(name = "external_location", nullable = false)
    private boolean externalLocation = false;

    @Column(name = "custom_barcode", length = 128)
    private String customBarcode;

    public static String compactLabelFor(UUID id) {
        String hex = id.toString().replace("-", "");
        return "l_" + hex.substring(hex.length() - 12);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getPathString() {
        return pathString;
    }

    public void setPathString(String pathString) {
        this.pathString = pathString;
    }

    public String getLocationType() {
        return locationType;
    }

    public void setLocationType(String locationType) {
        this.locationType = locationType;
    }

    public boolean isStructural() {
        return structural;
    }

    public void setStructural(boolean structural) {
        this.structural = structural;
    }

    public boolean isExternalLocation() {
        return externalLocation;
    }

    public void setExternalLocation(boolean externalLocation) {
        this.externalLocation = externalLocation;
    }

    public String getCustomBarcode() {
        return customBarcode;
    }

    public void setCustomBarcode(String customBarcode) {
        this.customBarcode = customBarcode;
    }
}
