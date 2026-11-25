# 05 — Developer Playbook

Practical step-by-step recipes for extending `ims-core-management` without tripping ArchUnit or multi-tenancy guardrails.

---

## 1. Adding a New Tenant Entity & Flyway Migration

1. **Create Migration**: Add `src/main/resources/db/migration/V{N}__{description}.sql`.
   - Always include `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL REFERENCES tenants(id)`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`, `created_by UUID`, `updated_by UUID`, `metadata JSONB NOT NULL DEFAULT '{}'`, and `version BIGINT NOT NULL DEFAULT 0` (if versioned).
   - Index `(tenant_id, ...)` on all foreign keys and lookup columns.
2. **Create Entity**: Place the class in `com.ims.<module>.domain.model`.
   - Extend `VersionedTenantEntity` (default) or `BaseTenantEntity` (only for append-only ledgers or high-contention summary counters).
   - Add `@Audited` if mutations should appear in the tenant audit log; annotate sensitive or noisy fields with `@AuditIgnore`.
3. **Register Table in `TenantPredicateInspector`**:
   - Add the new table name to `TENANT_SCOPED_TABLES` in `com.ims.config.tenant.TenantPredicateInspector` so the SQL safety net protects it at runtime.
4. **Create Repository**: Place the Spring Data JPA interface in `com.ims.<module>.infrastructure.persistence`. Never use `@Query(nativeQuery = true)` or `getReferenceById`.

---

## 2. Adding a Protected Controller Endpoint

1. Place the `@RestController` in `com.ims.<module>.api` (never expose `/api/platform/**` outside `com.ims.platform`).
2. Annotate the controller class (or method) with `@RequiresFeature(Feature.<KEY>)`.
3. Annotate each handler method with `@PreAuthorize("hasAuthority('<DOMAIN>:<ACTION>')")` using codes from `com.ims.identity.domain.model.Permission`.
4. Validate request bodies with `@Valid` + Bean Validation annotations; throw `DomainException` subclasses from `application` / `domain` (never `ResponseStatusException`).
5. If creating a quota-limited resource, call `EntitlementFacade.checkLimit(...)` inside the `@Transactional` service before persisting.

---

## 3. Exposing or Consuming a Cross-Module `spi` Port

- **Never** import another module's `domain`, `application`, `infrastructure`, or `api` packages.
- **Providing a port**: Define the interface and immutable DTO records in `com.ims.<provider>.spi`. Implement it in `com.ims.<provider>.application` or `infrastructure.adapter`.
- **Implementing a sibling SPI (Driving Adapter)** — e.g., `StockImportHandler` implementing `ImportTargetHandler` from `collaboration.spi`:
  - Place the adapter in `com.ims.<consumer>.application.adapter` (not `infrastructure.adapter`) if `application` services don't depend on it, or keep `spi` free of `application` types so `SlicesRuleDefinition` stays cycle-free.

---

## 4. Writing Integration & Architecture Tests

- Integration tests extend the Testcontainers PostgreSQL base setup and bind `TenantContext` during setup/assertions.
- Always run the full verification suite after structural changes:
  ```bash
  mvn clean test
  ```
- Confirm **all** ArchUnit tests (`ArchitectureBoundaryTest`, `CleanArchitectureRulesTest`, `DataAccessRulesTest`) and Testcontainers specs pass with 0 failures.
