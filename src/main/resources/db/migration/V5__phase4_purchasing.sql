-- V5__phase4_purchasing.sql
-- Phase 4: Inbound Purchasing & Receiving (Purchase Orders, Line Items, Extra Lines)

CREATE TABLE purchase_orders (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    reference               VARCHAR(64) NOT NULL,
    description             VARCHAR(500),
    supplier_id             UUID NOT NULL,
    supplier_reference      VARCHAR(120),
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, PLACED, IN_PROGRESS, COMPLETE, ON_HOLD, CANCELLED
    currency                VARCHAR(3) NOT NULL DEFAULT 'USD',
    destination_location_id UUID,
    issue_date              DATE,
    target_date             DATE,
    complete_date           DATE,
    notes                   TEXT,
    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by              UUID,
    updated_by              UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reference),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, destination_location_id) REFERENCES stock_locations(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_purchase_orders_tenant_status ON purchase_orders(tenant_id, status);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders(tenant_id, supplier_id);

CREATE TABLE purchase_order_line_items (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    purchase_order_id       UUID NOT NULL,
    supplier_part_id        UUID NOT NULL,
    part_id                 UUID NOT NULL,
    quantity                NUMERIC(19, 4) NOT NULL, -- Ordered packs
    received                NUMERIC(19, 4) NOT NULL DEFAULT 0, -- Received packs
    pack_quantity           NUMERIC(19, 4) NOT NULL DEFAULT 1,
    unit_price              NUMERIC(19, 6) NOT NULL DEFAULT 0, -- Price per pack in PO currency
    currency                VARCHAR(3) NOT NULL DEFAULT 'USD',
    destination_location_id UUID,
    target_date             DATE,
    reference               VARCHAR(120),
    notes                   VARCHAR(500),
    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by              UUID,
    updated_by              UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, supplier_part_id) REFERENCES supplier_parts(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, destination_location_id) REFERENCES stock_locations(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_po_lines_po ON purchase_order_line_items(tenant_id, purchase_order_id);
CREATE INDEX idx_po_lines_part ON purchase_order_line_items(tenant_id, part_id);

CREATE TABLE purchase_order_extra_lines (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    purchase_order_id   UUID NOT NULL,
    reference           VARCHAR(120),
    description         VARCHAR(500) NOT NULL,
    quantity            NUMERIC(19, 4) NOT NULL DEFAULT 1,
    unit_price          NUMERIC(19, 6) NOT NULL DEFAULT 0,
    currency            VARCHAR(3) NOT NULL DEFAULT 'USD',
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_po_extra_lines_po ON purchase_order_extra_lines(tenant_id, purchase_order_id);
