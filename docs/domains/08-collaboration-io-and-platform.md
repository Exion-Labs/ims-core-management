# 08 — Collaboration, Bulk I/O, Webhooks, Reports & Platform Console

Covers cross-cutting collaboration tools, bulk data pipelines, outbound integrations, custom PDF reporting, and the multi-tenant Platform Operator Console.

---

## 1. Collaboration & Unified Search (`com.ims.collaboration`)
- **Attachments (`AttachmentService`)**: Polymorphic file attachments (`PART`, `STOCK_ITEM`, `PURCHASE_ORDER`, `BUILD_ORDER`, `SALES_ORDER`, `COMPANY`). Uses two-step S3 presigned URLs (`POST /attachments/presign` -> client PUT to S3 -> `POST /attachments/{id}/confirm`), enforcing `Feature.STORAGE_MB` quota before issuing upload tickets.
- **Notes (`NoteService`)**: Polymorphic rich-text notes indexed with PostgreSQL `pg_trgm` for fast substring matching.
- **Global Search (`GlobalSearchService`)**: Fan-out query across all registered `SearchContributor` beans (Parts, Categories, Companies, Stock Locations, POs, Build Orders, Sales Orders) for the UI Command Palette.

---

## 2. Bulk Import / Export (`ImportExportService`)
- Gated by `Feature.IMPORT_EXPORT`.
- **2-Phase Import**:
  1. **Preview**: Parses uploaded CSV or XLSX (FastExcel with zip-bomb guard), detects headers, suggests column mappings, and validates rows without mutating DB state.
  2. **Commit**: Dispatches validated rows to the domain's `ImportTargetHandler` (`PARTS`, `CATEGORIES`, `COMPANIES`, `STOCK`, etc.) inside a transaction.
- **Export**: Streams CSV/XLSX files via `TabularExportPort` with formula-injection sanitization (`=`, `+`, `-`, `@` prefix escaping).

---

## 3. Real-Time Events, Webhooks & PDF Reports (`com.ims.integration`)
- **Real-Time SSE (`Feature.REALTIME`)**: Clients exchange an authenticated session for a short-lived SSE ticket (`POST /api/v1/events/ticket`) and subscribe to `GET /api/v1/events?ticket=...` for live table invalidation events.
- **Webhooks (`Feature.WEBHOOKS`, requires `API_ACCESS`)**: Tenants register `WebhookSubscription`s (`MAX_WEBHOOKS` quota) for domain events (`PART_CREATED`, `ENTITLEMENTS_CHANGED`, etc.). Deliveries run asynchronously via `OutboxDispatcher` with SSRF URL validation and HMAC-SHA256 signatures.
- **Custom Reports (`Feature.CUSTOM_REPORTS`)**: Handlebars HTML templates rendered to PDF via OpenHTMLToPDF using `ReportDataProvider` contexts (e.g., PO, Build Traveler, Packing List, Stock Labels).

---

## 4. Platform Operator Console (`com.ims.platform`)
Isolated behind `/api/platform/**` (`PlatformAuthFilter`) and `PlatformUser` accounts:
- **Tenant Lifecycle**: Provision tenants, inspect tenant details, suspend (`SUSPENDED`), reactivate (`ACTIVE`), or soft-delete (`DELETED`) tenants.
- **Plan & Override Management**: Switch tenant `Plan`s (`TRIAL`, `STARTER`, `PRO`, `ENTERPRISE`) and grant time-bound or permanent `TenantFeatureOverride`s.
- **Usage Telemetry**: Daily `TenantUsageSnapshot` aggregation across `TenantUsageContributor`s (`users`, `parts`, `locations`, `storageMb`, `webhooks`).
- **Support Impersonation (`SupportSession`)**: Issues an audited, **30-minute read-only** tenant JWT with mandatory reason logging in `PlatformAuditLog`.
- **Outbox Operations**: Inspect `DEAD_LETTER` `OutboxEvent`s and trigger manual retries.
