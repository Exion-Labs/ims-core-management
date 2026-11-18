-- V6__phase5_bom_and_manufacturing.sql
-- Phase 5: Bill of Materials (BOM) & Manufacturing Build Orders

CREATE TABLE bom_items (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    part_id         UUID NOT NULL,
    sub_part_id     UUID NOT NULL,
    quantity        NUMERIC(19, 4) NOT NULL,
    reference       VARCHAR(250),
    optional        BOOLEAN NOT NULL DEFAULT FALSE,
    consumable      BOOLEAN NOT NULL DEFAULT FALSE,
    allow_variants  BOOLEAN NOT NULL DEFAULT TRUE,
    inherited       BOOLEAN NOT NULL DEFAULT FALSE,
    notes           VARCHAR(500),
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, part_id, sub_part_id),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, sub_part_id) REFERENCES parts(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_bom_items_part ON bom_items(tenant_id, part_id);
CREATE INDEX idx_bom_items_subpart ON bom_items(tenant_id, sub_part_id);

CREATE TABLE bom_item_substitutes (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    bom_item_id     UUID NOT NULL,
    part_id         UUID NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, bom_item_id, part_id),
    FOREIGN KEY (tenant_id, bom_item_id) REFERENCES bom_items(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE build_orders (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    reference               VARCHAR(64) NOT NULL,
    title                   VARCHAR(250),
    part_id                 UUID NOT NULL,
    quantity                NUMERIC(19, 4) NOT NULL,
    completed               NUMERIC(19, 4) NOT NULL DEFAULT 0,
    parent_build_id         UUID,
    sales_order_id          UUID,
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, PRODUCTION, COMPLETED, ON_HOLD, CANCELLED
    source_location_id      UUID,
    destination_location_id UUID,
    target_date             DATE,
    completion_date         DATE,
    notes                   TEXT,
    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by              UUID,
    updated_by              UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reference),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, parent_build_id) REFERENCES build_orders(tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, source_location_id) REFERENCES stock_locations(tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, destination_location_id) REFERENCES stock_locations(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_build_orders_tenant_status ON build_orders(tenant_id, status);
CREATE INDEX idx_build_orders_part ON build_orders(tenant_id, part_id);

CREATE TABLE build_lines (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    build_order_id  UUID NOT NULL,
    bom_item_id     UUID,
    sub_part_id     UUID NOT NULL,
    quantity        NUMERIC(19, 4) NOT NULL,
    consumed        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    reference       VARCHAR(250),
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, build_order_id) REFERENCES build_orders(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, bom_item_id) REFERENCES bom_items(tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, sub_part_id) REFERENCES parts(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_build_lines_build ON build_lines(tenant_id, build_order_id);

CREATE TABLE build_allocations (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    build_order_id  UUID NOT NULL,
    build_line_id   UUID NOT NULL,
    stock_item_id   UUID NOT NULL,
    install_into_id UUID,
    quantity        NUMERIC(19, 4) NOT NULL,
    consumed        BOOLEAN NOT NULL DEFAULT FALSE,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, build_order_id) REFERENCES build_orders(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, build_line_id) REFERENCES build_lines(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, stock_item_id) REFERENCES stock_items(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, install_into_id) REFERENCES stock_items(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_build_allocations_build ON build_allocations(tenant_id, build_order_id);
CREATE INDEX idx_build_allocations_stock ON build_allocations(tenant_id, stock_item_id);
