-- Phase 6: Outbound Sales, Shipments, Transfers & Returns

CREATE TABLE sales_orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    reference VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    customer_reference VARCHAR(128),
    description VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    order_date DATE,
    target_date DATE,
    shipment_date DATE,
    notes TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, reference),
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_so_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES companies(tenant_id, id)
);

CREATE INDEX idx_so_tenant_status ON sales_orders(tenant_id, status);

CREATE TABLE sales_order_line_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    sales_order_id UUID NOT NULL,
    part_id UUID NOT NULL,
    quantity NUMERIC(19, 6) NOT NULL,
    shipped NUMERIC(19, 6) NOT NULL DEFAULT 0,
    sale_price NUMERIC(19, 6),
    sale_price_currency VARCHAR(3),
    reference VARCHAR(128),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_soli_so FOREIGN KEY (tenant_id, sales_order_id) REFERENCES sales_orders(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_soli_part FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id)
);

CREATE INDEX idx_soli_so ON sales_order_line_items(tenant_id, sales_order_id);

CREATE TABLE sales_order_shipments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    sales_order_id UUID NOT NULL,
    reference VARCHAR(64) NOT NULL,
    tracking_number VARCHAR(128),
    invoice_number VARCHAR(128),
    carrier VARCHAR(128),
    shipment_date DATE,
    delivery_date DATE,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_sos_so FOREIGN KEY (tenant_id, sales_order_id) REFERENCES sales_orders(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_sos_so ON sales_order_shipments(tenant_id, sales_order_id);

CREATE TABLE sales_order_allocations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    line_item_id UUID NOT NULL,
    shipment_id UUID,
    stock_item_id UUID NOT NULL,
    quantity NUMERIC(19, 6) NOT NULL,
    shipped BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_soa_line FOREIGN KEY (tenant_id, line_item_id) REFERENCES sales_order_line_items(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_soa_shipment FOREIGN KEY (tenant_id, shipment_id) REFERENCES sales_order_shipments(tenant_id, id) ON DELETE SET NULL,
    CONSTRAINT fk_soa_stock FOREIGN KEY (tenant_id, stock_item_id) REFERENCES stock_items(tenant_id, id)
);

CREATE INDEX idx_soa_line ON sales_order_allocations(tenant_id, line_item_id);
CREATE INDEX idx_soa_shipment ON sales_order_allocations(tenant_id, shipment_id);
CREATE INDEX idx_soa_stock ON sales_order_allocations(tenant_id, stock_item_id);

CREATE TABLE return_orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    reference VARCHAR(64) NOT NULL,
    customer_id UUID NOT NULL,
    sales_order_id UUID,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    description VARCHAR(500),
    target_date DATE,
    completion_date DATE,
    notes TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, reference),
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_ro_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES companies(tenant_id, id),
    CONSTRAINT fk_ro_so FOREIGN KEY (tenant_id, sales_order_id) REFERENCES sales_orders(tenant_id, id)
);

CREATE INDEX idx_ro_tenant_status ON return_orders(tenant_id, status);

CREATE TABLE return_order_line_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    return_order_id UUID NOT NULL,
    stock_item_id UUID NOT NULL,
    quantity NUMERIC(19, 6) NOT NULL DEFAULT 1,
    received_location_id UUID,
    outcome VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_roli_ro FOREIGN KEY (tenant_id, return_order_id) REFERENCES return_orders(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_roli_stock FOREIGN KEY (tenant_id, stock_item_id) REFERENCES stock_items(tenant_id, id),
    CONSTRAINT fk_roli_loc FOREIGN KEY (tenant_id, received_location_id) REFERENCES stock_locations(tenant_id, id)
);

CREATE INDEX idx_roli_ro ON return_order_line_items(tenant_id, return_order_id);

CREATE TABLE transfer_orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    reference VARCHAR(64) NOT NULL,
    source_location_id UUID NOT NULL,
    destination_location_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    description VARCHAR(500),
    target_date DATE,
    completion_date DATE,
    notes TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, reference),
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_to_src FOREIGN KEY (tenant_id, source_location_id) REFERENCES stock_locations(tenant_id, id),
    CONSTRAINT fk_to_dest FOREIGN KEY (tenant_id, destination_location_id) REFERENCES stock_locations(tenant_id, id)
);

CREATE INDEX idx_to_tenant_status ON transfer_orders(tenant_id, status);

CREATE TABLE transfer_order_line_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    transfer_order_id UUID NOT NULL,
    stock_item_id UUID NOT NULL,
    quantity NUMERIC(19, 6) NOT NULL,
    received BOOLEAN NOT NULL DEFAULT false,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    metadata JSONB NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id),
    CONSTRAINT fk_toli_to FOREIGN KEY (tenant_id, transfer_order_id) REFERENCES transfer_orders(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_toli_stock FOREIGN KEY (tenant_id, stock_item_id) REFERENCES stock_items(tenant_id, id)
);

CREATE INDEX idx_toli_to ON transfer_order_line_items(tenant_id, transfer_order_id);
