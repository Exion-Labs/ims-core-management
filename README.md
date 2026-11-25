# IMS Core Management (`ims-backend`)

Multi-tenant Inventory Management System (IMS) backend built with **Java 21**, **Spring Boot 3.5**, and **PostgreSQL 17**. It powers both the Tenant Portal (`@ims/client`) and the Platform Operator Console (`@ims/platform`) via strict BFF-guarded REST APIs and scoped Personal Access Tokens.

---

## Tech Stack

- **Runtime**: Java 21 (Virtual Threads enabled via `spring.threads.virtual.enabled=true`), Spring Boot 3.5.0
- **Database**: PostgreSQL 17 (`ltree`, `pg_trgm`), Flyway (`V1`–`V8`), Hibernate 6 with `@TenantId`
- **Security**: Spring Security 6 stateless filter chains, JJWT (`0.12.6`), BCrypt, BFF `X-Proxy-Secret`, rotating refresh tokens, `ims_pat_*` API tokens
- **Caching & Async**: Caffeine (`@EnableCaching`), Transactional Outbox (`FOR UPDATE SKIP LOCKED`), Server-Sent Events (SSE)
- **Domain Utilities**: Indriya (JSR-385 SI unit normalization), ZXing (QR/DataMatrix/Code128 barcodes), Jackson CSV + FastExcel (`0.18.4`), Handlebars + OpenHTMLToPDF, AWS SDK S3 Presigner
- **Verification**: JUnit 5, Testcontainers (`postgresql`), ArchUnit (`1.4.1`)

---

## Local Development

### Prerequisites
- **JDK 21** (`java -version` must resolve to 21)
- **Maven 3.9+**
- **Docker** (required for Testcontainers PostgreSQL integration tests)
- **PostgreSQL 17** (for local runtime; default `jdbc:postgresql://localhost:5432/ims`)

### Run Locally
```bash
mvn spring-boot:run
```
Swagger / OpenAPI UI is exposed at `http://localhost:8080/swagger-ui.html` and raw OpenAPI JSON at `http://localhost:8080/v3/api-docs`.

### Run Full Verification Suite (ArchUnit + Testcontainers)
```bash
mvn clean test
```
> **Note for IDE users**: If your IDE compiles classes in the background with a newer JDK (e.g., JDK 25), always run `mvn clean test` so Maven Surefire executes bytecode compiled for Java 21.

---

## Environment Variables

| Variable | Default (Dev) | Purpose |
| :--- | :--- | :--- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/ims` | PostgreSQL JDBC URL |
| `DB_USER` | `ims` | Database username |
| `DB_PASSWORD` | `ims_dev_password` | Database password |
| `PORT` | `8080` | HTTP server port |
| `IMS_SIGNUP_ENABLED` | `false` | Enables public self-service tenant registration (`POST /api/v1/auth/register-tenant`) |
| `IMS_JWT_SECRET` | Dev 32+ byte default | HMAC signing key for tenant, platform, and support JWTs |
| `IMS_PROXY_SECRET` | `ims-dev-bff-proxy-secret-2026` | Shared secret required on `X-Proxy-Secret` from Next.js BFFs |
| `IMS_S3_ENDPOINT` | `http://localhost:3900` | S3-compatible object storage endpoint |
| `IMS_S3_REGION` | `us-east-1` | Object storage region |
| `IMS_S3_BUCKET` | `ims-dev-files` | Bucket for attachments and generated report artifacts |
| `IMS_S3_ACCESS_KEY` | Dev key | S3 access key |
| `IMS_S3_SECRET_KEY` | Dev secret | S3 secret key |

---

## Documentation Index

### Suite 1 — Architecture & Best Practices (Developer Guide)
1. [01 — System Overview & Modular Monolith](docs/architecture/01-system-overview.md)
2. [02 — Multi-Tenancy & Security Model](docs/architecture/02-multitenancy-and-security.md)
3. [03 — Coding Standards & Automated Guardrails](docs/architecture/03-coding-standards-and-guardrails.md)
4. [04 — Async Outbox, Audit Trail & Integrations](docs/architecture/04-async-audit-and-integrations.md)
5. [05 — Developer Playbook](docs/architecture/05-developer-playbook.md)

### Suite 2 — Canonical Domain Requirements
0. [00 — Domain Map, Plans & Entitlements](docs/domains/00-domain-map-and-entitlements.md)
1. [01 — Identity, Tenancy & Governance](docs/domains/01-identity-tenancy-and-governance.md)
2. [02 — Engineering Catalog, BOM & Costing](docs/domains/02-catalog-bom-and-costing.md)
3. [03 — Business Partners, Sourcing & Multi-Currency](docs/domains/03-partners-sourcing-and-fx.md)
4. [04 — Warehouse, Stock Engine, QC & Barcodes](docs/domains/04-warehouse-stock-qc-and-barcodes.md)
5. [05 — Procurement & Inbound Receiving](docs/domains/05-purchasing-and-receiving.md)
6. [06 — Manufacturing & Build Orders](docs/domains/06-manufacturing-and-builds.md)
7. [07 — Fulfillment, Returns & Transfers](docs/domains/07-fulfillment-returns-and-transfers.md)
8. [08 — Collaboration, Bulk I/O, Webhooks, Reports & Platform Console](docs/domains/08-collaboration-io-and-platform.md)
