package com.ims.stock.domain.model;

import com.ims.common.VersionedTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Quality inspection pass/fail result recorded against a StockItem.
 */
@Entity
@Table(name = "stock_item_test_results")
public class StockItemTestResult extends VersionedTenantEntity {

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "test_name", nullable = false, length = 150)
    private String testName;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "value", length = 500)
    private String value;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "tested_by")
    private UUID testedBy;

    public UUID getStockItemId() {
        return stockItemId;
    }

    public void setStockItemId(UUID stockItemId) {
        this.stockItemId = stockItemId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(UUID attachmentId) {
        this.attachmentId = attachmentId;
    }

    public UUID getTestedBy() {
        return testedBy;
    }

    public void setTestedBy(UUID testedBy) {
        this.testedBy = testedBy;
    }
}
