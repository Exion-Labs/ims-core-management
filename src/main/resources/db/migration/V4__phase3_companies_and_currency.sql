-- V4__phase3_companies_and_currency.sql
-- Phase 3: Companies, Contacts, Addresses, Manufacturer Parts, Supplier Parts (pack-size conversion),
-- Tiered Supplier Price Breaks, and Multi-Currency Exchange Rates

-- Global Exchange Rate Table (@GlobalEntity in allowlist)
CREATE TABLE exchange_rates (
    id              UUID PRIMARY KEY,
    base_currency   VARCHAR(3) NOT NULL DEFAULT 'USD',
    target_currency VARCHAR(3) NOT NULL,
    rate            NUMERIC(19, 6) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (base_currency, target_currency)
);

CREATE TABLE companies (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                VARCHAR(200) NOT NULL,
    code                VARCHAR(64),
    description         VARCHAR(500),
    website             VARCHAR(300),
    email               VARCHAR(255),
    phone               VARCHAR(80),
    tax_id              VARCHAR(100),
    default_currency    VARCHAR(3) NOT NULL DEFAULT 'USD',
    supplier            BOOLEAN NOT NULL DEFAULT FALSE,
    manufacturer        BOOLEAN NOT NULL DEFAULT FALSE,
    customer            BOOLEAN NOT NULL DEFAULT FALSE,
    internal_company    BOOLEAN NOT NULL DEFAULT FALSE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_companies_tenant ON companies(tenant_id);

CREATE TABLE contacts (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    company_id          UUID NOT NULL,
    name                VARCHAR(150) NOT NULL,
    role_title          VARCHAR(120),
    email               VARCHAR(255),
    phone               VARCHAR(80),
    primary_contact     BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_contacts_company ON contacts(tenant_id, company_id);

CREATE TABLE addresses (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    company_id          UUID NOT NULL,
    label               VARCHAR(100) NOT NULL,
    line1               VARCHAR(250) NOT NULL,
    line2               VARCHAR(250),
    city                VARCHAR(120),
    state_region        VARCHAR(120),
    postal_code         VARCHAR(40),
    country             VARCHAR(80) NOT NULL DEFAULT 'US',
    primary_shipping    BOOLEAN NOT NULL DEFAULT FALSE,
    primary_billing     BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, company_id) REFERENCES companies(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_addresses_company ON addresses(tenant_id, company_id);

CREATE TABLE manufacturer_parts (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    part_id             UUID NOT NULL,
    manufacturer_id     UUID NOT NULL,
    mpn                 VARCHAR(120) NOT NULL,
    description         VARCHAR(500),
    datasheet_url       VARCHAR(500),
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, manufacturer_id, mpn),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, manufacturer_id) REFERENCES companies(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_manufacturer_parts_part ON manufacturer_parts(tenant_id, part_id);
CREATE INDEX idx_manufacturer_parts_mfr ON manufacturer_parts(tenant_id, manufacturer_id);

CREATE TABLE supplier_parts (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    part_id                 UUID NOT NULL,
    supplier_id             UUID NOT NULL,
    manufacturer_part_id    UUID,
    sku                     VARCHAR(120) NOT NULL,
    description             VARCHAR(500),
    pack_quantity           NUMERIC(19, 4) NOT NULL DEFAULT 1,
    moq                     NUMERIC(19, 4) NOT NULL DEFAULT 1,
    multiple                NUMERIC(19, 4) NOT NULL DEFAULT 1,
    lead_time_days          INT,
    currency                VARCHAR(3) NOT NULL DEFAULT 'USD',
    custom_barcode          VARCHAR(128),
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by              UUID,
    updated_by              UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, supplier_id, sku),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES companies(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, manufacturer_part_id) REFERENCES manufacturer_parts(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_supplier_parts_part ON supplier_parts(tenant_id, part_id);
CREATE INDEX idx_supplier_parts_supplier ON supplier_parts(tenant_id, supplier_id);
CREATE INDEX idx_supplier_parts_barcode ON supplier_parts(tenant_id, custom_barcode);

CREATE TABLE supplier_price_breaks (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    supplier_part_id    UUID NOT NULL,
    min_quantity        NUMERIC(19, 4) NOT NULL DEFAULT 1,
    unit_price          NUMERIC(19, 6) NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'USD',
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, supplier_part_id, min_quantity),
    FOREIGN KEY (tenant_id, supplier_part_id) REFERENCES supplier_parts(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_supplier_price_breaks_sp ON supplier_price_breaks(tenant_id, supplier_part_id, min_quantity ASC);
