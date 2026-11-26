-- Idempotency records: stores the committed response of a mutating request so that a retry
-- with the same Idempotency-Key replays it instead of executing the operation again.
-- A row only becomes visible once the business transaction commits (inserted in the same tx).
CREATE TABLE idempotency_records (
    id               UUID         PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    principal_id     UUID         NOT NULL,      -- apiTokenId for PATs, otherwise userId
    idem_key         VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,      -- SHA-256 hex of method + URI + arguments
    response_status  INT,                        -- NULL only inside the uncommitted transaction
    response_body    JSONB,
    created_at       TIMESTAMPTZ  NOT NULL,      -- TTL is measured from here
    -- inherited from BaseTenantEntity
    updated_at       TIMESTAMPTZ  NOT NULL,
    created_by       UUID,
    updated_by       UUID,
    metadata         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_idempotency_records_key UNIQUE (tenant_id, principal_id, idem_key)
);

CREATE INDEX ix_idempotency_records_created ON idempotency_records (tenant_id, created_at);
