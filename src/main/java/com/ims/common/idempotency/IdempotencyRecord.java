package com.ims.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.ims.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Committed outcome of an idempotent request, scoped by (tenant, principal, key).
 * Inserted in the same transaction as the business change, so it is only visible once
 * the operation has fully committed.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord extends BaseTenantEntity {

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(name = "idem_key", nullable = false, updatable = false, length = 255)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private JsonNode responseBody;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(UUID principalId, String idemKey, String requestHash) {
        this.principalId = principalId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public JsonNode getResponseBody() {
        return responseBody;
    }

    public void complete(int status, JsonNode body) {
        this.responseStatus = status;
        this.responseBody = body;
    }
}
