package com.ims.part;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "bom_item_substitutes")
@Audited
public class BomItemSubstitute extends VersionedTenantEntity {

    @Column(name = "bom_item_id", nullable = false)
    private UUID bomItemId;

    @Column(name = "part_id", nullable = false)
    private UUID partId;

    public UUID getBomItemId() {
        return bomItemId;
    }

    public void setBomItemId(UUID bomItemId) {
        this.bomItemId = bomItemId;
    }

    public UUID getPartId() {
        return partId;
    }

    public void setPartId(UUID partId) {
        this.partId = partId;
    }
}
