package com.ims.part;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import com.ims.config.hibernate.LtreeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.util.UUID;

/**
 * Hierarchical part category using PostgreSQL ltree path with compact alphanumeric labels.
 */
@Entity
@Table(name = "part_categories")
@Audited
public class PartCategory extends VersionedTenantEntity {

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

    @Column(name = "structural", nullable = false)
    private boolean structural = false;

    @Column(name = "default_location_id")
    private UUID defaultLocationId;

    @Column(name = "icon", length = 80)
    private String icon;

    /**
     * Generates a compact, deterministic ltree label from the category's UUID (e.g. "c_019a8f12b4c0").
     */
    public static String compactLabelFor(UUID id) {
        String hex = id.toString().replace("-", "");
        return "c_" + hex.substring(hex.length() - 12);
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

    public boolean isStructural() {
        return structural;
    }

    public void setStructural(boolean structural) {
        this.structural = structural;
    }

    public UUID getDefaultLocationId() {
        return defaultLocationId;
    }

    public void setDefaultLocationId(UUID defaultLocationId) {
        this.defaultLocationId = defaultLocationId;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }
}
