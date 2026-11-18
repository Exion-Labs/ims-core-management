-- V1__init_foundation.sql
-- Phase 0: Extensions, Multi-Tenancy Foundation, Auth, RBAC, Entitlements, Outbox, Attachments, Notes, and Immutable Audit Trail

CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =========================================================================
-- 1. GLOBAL ENTITIES (No @TenantId automatic discriminator)
-- =========================================================================

CREATE TABLE tenants (
    id              UUID PRIMARY KEY,
    slug            VARCHAR(80) NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, DELETED
    base_currency   VARCHAR(3) NOT NULL DEFAULT 'USD',
    timezone        VARCHAR(64) NOT NULL DEFAULT 'UTC',
    logo_url        VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    email           VARCHAR(255) NOT NULL UNIQUE,
    full_name       VARCHAR(200) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, INVITED, DISABLED
    invite_token    VARCHAR(128),
    reset_token     VARCHAR(128),
    reset_expires_at TIMESTAMPTZ,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_users_tenant_id ON users(tenant_id);

CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

CREATE TABLE api_tokens (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL,
    name            VARCHAR(150) NOT NULL,
    token_prefix    VARCHAR(20) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    scopes          VARCHAR(500) NOT NULL,
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) ON DELETE CASCADE,
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_api_tokens_tenant_id ON api_tokens(tenant_id);

-- Plans & Entitlements (Global catalog & per-tenant subscriptions/overrides)
CREATE TABLE plans (
    id              UUID PRIMARY KEY,
    code            VARCHAR(50) NOT NULL UNIQUE, -- TRIAL, STARTER, PRO, ENTERPRISE
    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE plan_features (
    id              UUID PRIMARY KEY,
    plan_id         UUID NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    feature_key     VARCHAR(80) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    limit_value     BIGINT,
    UNIQUE (plan_id, feature_key)
);

CREATE TABLE tenant_subscriptions (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    plan_id         UUID NOT NULL REFERENCES plans(id),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- TRIAL, ACTIVE, PAST_DUE, CANCELLED
    starts_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ends_at         TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tenant_feature_overrides (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    feature_key     VARCHAR(80) NOT NULL,
    enabled         BOOLEAN NOT NULL,
    limit_value     BIGINT,
    reason          VARCHAR(500) NOT NULL,
    expires_at      TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, feature_key)
);
CREATE INDEX idx_tenant_feature_overrides_tenant ON tenant_feature_overrides(tenant_id);

-- Transactional Outbox (Global entity polled with FOR UPDATE SKIP LOCKED)
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    event_type      VARCHAR(120) NOT NULL,
    aggregate_type  VARCHAR(80) NOT NULL,
    aggregate_id    UUID NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, PROCESSING, PUBLISHED, DEAD_LETTER
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events(status, next_attempt_at);
CREATE INDEX idx_outbox_tenant ON outbox_events(tenant_id, created_at);

-- =========================================================================
-- 2. TENANT-SCOPED ENTITIES (Filtered automatically via Hibernate @TenantId)
-- =========================================================================

CREATE TABLE roles (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(300),
    system_role     BOOLEAN NOT NULL DEFAULT FALSE,
    permissions     JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_roles_tenant_id ON roles(tenant_id);

CREATE TABLE user_role_assignments (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL,
    role_id         UUID NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, user_id, role_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, role_id) REFERENCES roles(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_user_role_assignments_tenant_user ON user_role_assignments(tenant_id, user_id);

CREATE TABLE tenant_settings (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    setting_key     VARCHAR(120) NOT NULL,
    setting_value   TEXT NOT NULL,
    value_type      VARCHAR(32) NOT NULL DEFAULT 'STRING', -- STRING, INTEGER, BOOLEAN, JSON
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, setting_key)
);
CREATE INDEX idx_tenant_settings_tenant ON tenant_settings(tenant_id);

CREATE TABLE reference_sequences (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sequence_key    VARCHAR(50) NOT NULL, -- PO, BO, SO, RMA, TO
    prefix          VARCHAR(20) NOT NULL,
    padding         INT NOT NULL DEFAULT 4,
    next_value      BIGINT NOT NULL DEFAULT 1,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sequence_key)
);
CREATE INDEX idx_reference_sequences_tenant ON reference_sequences(tenant_id);

CREATE TABLE attachments (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    entity_type     VARCHAR(80) NOT NULL,
    entity_id       UUID NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    content_type    VARCHAR(150) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    object_key      VARCHAR(500) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, READY
    comment         VARCHAR(500),
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_attachments_tenant_entity ON attachments(tenant_id, entity_type, entity_id);

CREATE TABLE notes (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    entity_type     VARCHAR(80) NOT NULL,
    entity_id       UUID NOT NULL,
    content         TEXT NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_notes_tenant_entity ON notes(tenant_id, entity_type, entity_id);
CREATE INDEX idx_notes_content_trgm ON notes USING GIN (content gin_trgm_ops);

-- Immutable Audit Event Table
CREATE TABLE audit_event (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_type          VARCHAR(32) NOT NULL, -- USER, API_TOKEN, SYSTEM_JOB, PLATFORM_SUPPORT
    actor_id            UUID,
    actor_name          VARCHAR(200),
    support_session_id  UUID,
    request_id          VARCHAR(100),
    ip                  VARCHAR(64),
    entity_type         VARCHAR(80) NOT NULL,
    entity_id           UUID,
    action              VARCHAR(64) NOT NULL,
    changes             JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason              VARCHAR(500),
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_audit_event_entity ON audit_event(tenant_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_event_actor ON audit_event(tenant_id, actor_id, occurred_at DESC);
CREATE INDEX idx_audit_event_occurred_brin ON audit_event USING BRIN(occurred_at);

-- Database-level immutability trigger on audit_event (blocks UPDATE and DELETE in addition to ArchUnit checks)
CREATE OR REPLACE FUNCTION prevent_audit_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is immutable and cannot be updated or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_immutable
BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_mutation();
