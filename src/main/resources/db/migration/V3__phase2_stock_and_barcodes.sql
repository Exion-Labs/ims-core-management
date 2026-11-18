-- V3__phase2_stock_and_barcodes.sql
-- Phase 2: Warehouse Stock Locations (ltree), Stock Items, Immutable StockItemTracking,
-- PartStockSummary (batched delta counters + generated available_stock), Quality Test Templates & Results

CREATE TABLE stock_locations (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                VARCHAR(150) NOT NULL,
    description         VARCHAR(500),
    parent_id           UUID,
    path                ltree NOT NULL,
    path_string         VARCHAR(1000) NOT NULL,
    location_type       VARCHAR(50) NOT NULL DEFAULT 'BIN', -- WAREHOUSE, ROOM, AISLE, RACK, SHELF, BIN, QUARANTINE, CUSTOMER
    structural          BOOLEAN NOT NULL DEFAULT FALSE,
    external_location   BOOLEAN NOT NULL DEFAULT FALSE,
    custom_barcode      VARCHAR(128),
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES stock_locations(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_stock_locations_tenant ON stock_locations(tenant_id);
CREATE INDEX idx_stock_locations_parent ON stock_locations(tenant_id, parent_id);
CREATE INDEX idx_stock_locations_path_gist ON stock_locations USING GIST (path);
CREATE INDEX idx_stock_locations_barcode ON stock_locations(tenant_id, custom_barcode);

CREATE TABLE stock_items (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    part_id             UUID NOT NULL,
    location_id         UUID,
    parent_item_id      UUID,
    installed_in_id     UUID,
    customer_id         UUID,
    quantity            NUMERIC(19, 4) NOT NULL,
    serial_number       VARCHAR(100),
    batch_code          VARCHAR(100),
    status              VARCHAR(32) NOT NULL DEFAULT 'OK', -- OK, ATTENTION, DAMAGED, DESTROYED, REJECTED, LOST, QUARANTINED, RETURNED, IN_TRANSIT
    expiry_date         DATE,
    purchase_price      NUMERIC(19, 4),
    currency            VARCHAR(3),
    packaging           VARCHAR(120),
    is_building         BOOLEAN NOT NULL DEFAULT FALSE,
    custom_barcode      VARCHAR(128),
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, location_id) REFERENCES stock_locations(tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, parent_item_id) REFERENCES stock_items(tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, installed_in_id) REFERENCES stock_items(tenant_id, id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uq_stock_items_part_serial
    ON stock_items(tenant_id, part_id, serial_number)
    WHERE serial_number IS NOT NULL;

CREATE INDEX idx_stock_items_tenant_part ON stock_items(tenant_id, part_id);
CREATE INDEX idx_stock_items_tenant_location ON stock_items(tenant_id, location_id);
CREATE INDEX idx_stock_items_parent ON stock_items(tenant_id, parent_item_id);
CREATE INDEX idx_stock_items_installed_in ON stock_items(tenant_id, installed_in_id);
CREATE INDEX idx_stock_items_barcode ON stock_items(tenant_id, custom_barcode);

CREATE TABLE stock_item_tracking (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stock_item_id       UUID NOT NULL,
    part_id             UUID NOT NULL,
    tracking_type       VARCHAR(40) NOT NULL,
    quantity_delta      NUMERIC(19, 4) NOT NULL,
    quantity_after      NUMERIC(19, 4) NOT NULL,
    location_id         UUID,
    notes               VARCHAR(500),
    user_id             UUID,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, stock_item_id) REFERENCES stock_items(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_stock_tracking_item ON stock_item_tracking(tenant_id, stock_item_id, created_at DESC);
CREATE INDEX idx_stock_tracking_created_brin ON stock_item_tracking USING BRIN(created_at);

-- PartStockSummary: high-contention summary counter table (NO @Version optimistic lock; updated via sorted HQL bulk deltas)
CREATE TABLE part_stock_summaries (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    part_id             UUID NOT NULL,
    in_stock            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    allocated_to_builds NUMERIC(19, 4) NOT NULL DEFAULT 0,
    allocated_to_sales  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    on_order            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    in_production       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    available_stock     NUMERIC(19, 4) GENERATED ALWAYS AS (GREATEST(0, in_stock - allocated_to_builds - allocated_to_sales)) STORED,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, part_id),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_part_stock_summaries_tenant_part ON part_stock_summaries(tenant_id, part_id);

-- Backfill PartStockSummary rows for any existing parts
INSERT INTO part_stock_summaries (id, tenant_id, part_id, in_stock, allocated_to_builds, allocated_to_sales, on_order, in_production)
SELECT p.id, p.tenant_id, p.id, 0, 0, 0, 0, 0
FROM parts p
ON CONFLICT (tenant_id, part_id) DO NOTHING;

CREATE TABLE part_test_templates (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    part_id             UUID NOT NULL,
    test_name           VARCHAR(150) NOT NULL,
    description         VARCHAR(500),
    required            BOOLEAN NOT NULL DEFAULT TRUE,
    requires_value      BOOLEAN NOT NULL DEFAULT FALSE,
    requires_attachment BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, part_id, test_name),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_part_test_templates_part ON part_test_templates(tenant_id, part_id);

CREATE TABLE stock_item_test_results (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    stock_item_id       UUID NOT NULL,
    template_id         UUID,
    test_name           VARCHAR(150) NOT NULL,
    passed              BOOLEAN NOT NULL,
    value               VARCHAR(500),
    notes               VARCHAR(500),
    attachment_id       UUID,
    tested_by           UUID,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, stock_item_id) REFERENCES stock_items(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, template_id) REFERENCES part_test_templates(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_stock_test_results_item ON stock_item_test_results(tenant_id, stock_item_id, created_at DESC);
