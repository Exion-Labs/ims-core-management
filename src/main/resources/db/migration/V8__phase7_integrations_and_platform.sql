-- Phase 7 & Phase P: Pricing Rollups, Custom PDF Reports, Webhooks, SSE & Platform Admin Console

CREATE TABLE webhook_subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(150) NOT NULL,
    target_url VARCHAR(1024) NOT NULL,
    secret VARCHAR(255) NOT NULL,
    event_types JSONB NOT NULL DEFAULT '[]',
    enabled BOOLEAN NOT NULL DEFAULT true,
    last_delivery_at TIMESTAMPTZ,
    last_status_code INTEGER,
    failure_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_webhook_subs_tenant ON webhook_subscriptions(tenant_id, enabled);

CREATE TABLE report_templates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(150) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    template_html TEXT NOT NULL,
    page_size VARCHAR(32) NOT NULL DEFAULT 'A4',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_report_templates_tenant ON report_templates(tenant_id, entity_type);

CREATE TABLE platform_users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(64) NOT NULL DEFAULT 'PLATFORM_ADMIN',
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tenant_usage_snapshots (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    snapshot_date DATE NOT NULL,
    parts_count BIGINT NOT NULL DEFAULT 0,
    locations_count BIGINT NOT NULL DEFAULT 0,
    users_count BIGINT NOT NULL DEFAULT 0,
    stock_items_count BIGINT NOT NULL DEFAULT 0,
    orders_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, snapshot_date)
);

CREATE TABLE support_sessions (
    id UUID PRIMARY KEY,
    platform_user_id UUID NOT NULL REFERENCES platform_users(id),
    target_tenant_id UUID NOT NULL REFERENCES tenants(id),
    reason VARCHAR(500) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ
);

CREATE INDEX idx_support_sessions_tenant ON support_sessions(target_tenant_id, started_at DESC);

CREATE TABLE platform_audit_log (
    id UUID PRIMARY KEY,
    platform_user_id UUID,
    actor_email VARCHAR(255),
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id UUID,
    details JSONB NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_audit_occurred ON platform_audit_log(occurred_at DESC);
