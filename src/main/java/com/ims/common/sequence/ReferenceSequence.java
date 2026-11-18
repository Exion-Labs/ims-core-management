package com.ims.common.sequence;

import com.ims.common.VersionedTenantEntity;
import com.ims.common.audit.Audited;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Per-tenant sequential reference counter (e.g., PO-0001, BO-0001, SO-0001, RMA-0001, TO-0001).
 */
@Entity
@Audited(entityType = "ReferenceSequence")
@Table(name = "reference_sequences")
public class ReferenceSequence extends VersionedTenantEntity {

    @Column(name = "sequence_key", nullable = false, length = 50)
    private String sequenceKey;

    @Column(name = "prefix", nullable = false, length = 20)
    private String prefix;

    @Column(name = "padding", nullable = false)
    private int padding = 4;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1L;

    public String formatCurrentAndIncrement() {
        long val = this.nextValue;
        this.nextValue = val + 1;
        String formatPattern = "%s%0" + Math.max(1, padding) + "d";
        return String.format(formatPattern, prefix, val);
    }

    public String formatPreview() {
        String formatPattern = "%s%0" + Math.max(1, padding) + "d";
        return String.format(formatPattern, prefix, nextValue);
    }

    public String getSequenceKey() {
        return sequenceKey;
    }

    public void setSequenceKey(String sequenceKey) {
        this.sequenceKey = sequenceKey;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public int getPadding() {
        return padding;
    }

    public void setPadding(int padding) {
        this.padding = padding;
    }

    public long getNextValue() {
        return nextValue;
    }

    public void setNextValue(long nextValue) {
        this.nextValue = nextValue;
    }
}
