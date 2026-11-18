-- V2__phase1_part_catalog.sql
-- Phase 1: Part Categories (ltree), Parts (Variants & Revisions), Parameter Templates & Typed Range-Indexed Part Parameters

CREATE TABLE part_categories (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                VARCHAR(150) NOT NULL,
    description         VARCHAR(500),
    parent_id           UUID,
    path                ltree NOT NULL,
    path_string         VARCHAR(1000) NOT NULL,
    structural          BOOLEAN NOT NULL DEFAULT FALSE,
    default_location_id UUID,
    icon                VARCHAR(80),
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES part_categories(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_part_categories_tenant ON part_categories(tenant_id);
CREATE INDEX idx_part_categories_parent ON part_categories(tenant_id, parent_id);
CREATE INDEX idx_part_categories_path_gist ON part_categories USING GIST (path);

CREATE TABLE parts (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ipn                 VARCHAR(100) NOT NULL,
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    category_id         UUID,
    revision            VARCHAR(32) NOT NULL DEFAULT 'A',
    revision_of_id      UUID,
    variant_of_id       UUID,
    unit                VARCHAR(32) NOT NULL DEFAULT 'pcs',
    minimum_stock       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    locked              BOOLEAN NOT NULL DEFAULT FALSE,
    assembly            BOOLEAN NOT NULL DEFAULT FALSE,
    component           BOOLEAN NOT NULL DEFAULT TRUE,
    trackable           BOOLEAN NOT NULL DEFAULT FALSE,
    purchaseable        BOOLEAN NOT NULL DEFAULT TRUE,
    salable             BOOLEAN NOT NULL DEFAULT FALSE,
    virtual             BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, ipn, revision),
    FOREIGN KEY (tenant_id, category_id) REFERENCES part_categories(tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, revision_of_id) REFERENCES parts(tenant_id, id) ON DELETE SET NULL,
    FOREIGN KEY (tenant_id, variant_of_id) REFERENCES parts(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_parts_tenant_category ON parts(tenant_id, category_id);
CREATE INDEX idx_parts_tenant_variant ON parts(tenant_id, variant_of_id);
CREATE INDEX idx_parts_tenant_revision ON parts(tenant_id, revision_of_id);
CREATE INDEX idx_parts_ipn_trgm ON parts USING GIN (ipn gin_trgm_ops);
CREATE INDEX idx_parts_name_trgm ON parts USING GIN (name gin_trgm_ops);

CREATE TABLE parameter_templates (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                VARCHAR(150) NOT NULL,
    units               VARCHAR(50),
    description         VARCHAR(500),
    choices             VARCHAR(1000),
    checkbox            BOOLEAN NOT NULL DEFAULT FALSE,
    default_category_id UUID,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name),
    FOREIGN KEY (tenant_id, default_category_id) REFERENCES part_categories(tenant_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_parameter_templates_tenant ON parameter_templates(tenant_id);
CREATE INDEX idx_parameter_templates_category ON parameter_templates(tenant_id, default_category_id);

CREATE TABLE part_parameters (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    part_id             UUID NOT NULL,
    template_id         UUID NOT NULL,
    value_text          VARCHAR(500) NOT NULL,
    value_numeric       DOUBLE PRECISION,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, part_id, template_id),
    FOREIGN KEY (tenant_id, part_id) REFERENCES parts(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, template_id) REFERENCES parameter_templates(tenant_id, id) ON DELETE CASCADE
);

-- B-tree index on (tenant_id, template_id, value_numeric) so range queries like "capacitance between 1uF and 10uF" use an index scan
CREATE INDEX idx_part_parameters_numeric_range ON part_parameters(tenant_id, template_id, value_numeric);
CREATE INDEX idx_part_parameters_part ON part_parameters(tenant_id, part_id);
