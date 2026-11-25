# 03 — Coding Standards & Automated Guardrails

Architecture and tenant-safety conventions in `ims-core-management` are **not advisory**—they are verified on every build by ArchUnit suites in `src/test/java/com/ims/architecture/`:
- `ArchitectureBoundaryTest`
- `CleanArchitectureRulesTest`
- `DataAccessRulesTest`

---

## 1. Data-Access Rules (`DataAccessRulesTest`)

| Rule | Rationale |
| :--- | :--- |
| **No native SQL at runtime** (`@Query(nativeQuery=true)`, `EntityManager.createNativeQuery`, `Session.createNativeQuery`) | Hibernate `@TenantId` does not rewrite native SQL strings; native queries risk silent cross-tenant leaks. Use JPQL/HQL or registered `PostgresFunctionContributor` functions (e.g., `ltree` / `pg_trgm` operators). |
| **No `JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcClient`, or `DataSource`/`Connection` injection** | Prevents bypassing Hibernate session filters and `TenantPredicateInspector`. |
| **No `getReferenceById` or `EntityManager.getReference`** | Hibernate proxies created by `getReference*` skip tenant ownership checks until flush; always load entities explicitly via repository queries. |
| **Every `@Entity` extends `BaseTenantEntity` or uses `@GlobalEntity`** | Guarantees zero unpartitioned tables sneak into the schema. |

---

## 2. Clean Architecture & Boundary Rules (`CleanArchitectureRulesTest` & `ArchitectureBoundaryTest`)

- **Shared Kernel Purity**: `com.ims.shared..` must never depend on any business module.
- **Layer Placement**:
  - `@Entity` classes in migrated modules must reside in `..domain..`.
  - `@RestController` classes in migrated modules must reside in `..api..`.
  - `..domain..` must never depend on `..api..` or `..infrastructure..`.
- **Exception Discipline**:
  - `ResponseStatusException` is **forbidden** in `..domain..` and `..application..`.
  - Throw semantic subclasses of `com.ims.shared.exception.DomainException`:
    - `ResourceNotFoundException` -> `404 NOT_FOUND`
    - `ConflictException` -> `409 CONFLICT`
    - `BusinessRuleException` / `InvalidStateTransitionException` -> `422 UNPROCESSABLE_ENTITY`
    - `ValidationException` -> `400 BAD_REQUEST`
    - `UnauthorizedException` -> `401 UNAUTHORIZED`
    - `ForbiddenException` -> `403 FORBIDDEN`
    - `FeatureNotEnabledException` / `LimitReachedException` -> `403 FORBIDDEN` (includes `feature`, `limit`, `current` fields in JSON)
  - `GlobalExceptionHandler` serializes all errors uniformly as `{ "code": "...", "message": "...", "timestamp": "..." }`.
- **Feature Annotation Coverage**: Every `@RestController` in `catalog`, `stock`, `partner`, `manufacturing`, `purchasing`, `fulfillment`, and `integration` must declare `@RequiresFeature`.
- **Audit Log Immutability**: `AuditEventRepository` is checked via reflection and forbidden from declaring `delete*`, `remove*`, `update*`, or `saveAll*` methods.

---

## 3. Concurrency, Locking & Batch Read Patterns

### Deterministic Row Locking (`FOR UPDATE`)
High-contention stock mutations (`transfer`, `merge`, `build/sales allocation`) lock multiple `StockItem` rows pessimistically. To prevent PostgreSQL deadlocks when two concurrent transactions lock overlapping items in opposite order, **always sort target UUIDs in ascending order before acquiring `FOR UPDATE` locks**.

### Transaction-Batched Stock Summaries (`StockDeltaAccumulator`)
Updating `PartStockSummary` directly on every stock mutation creates hot-row contention. Instead:
- Mutations record net deltas in a transaction-scoped `StockDeltaAccumulator`.
- On transaction commit synchronization, deltas are flushed once per `(part_id)` (and rolled up to template parts).
- `StockReconciliationJob` runs on a schedule to repair any summary drift from `StockItem` ground truth.

### CQRS-Lite Batch Projections
Avoid N+1 repository calls when assembling list responses or cross-module summaries. Use dedicated read services (`CatalogQueryService`, `PartStockSummary` batch lookups, and `spi` read ports) that load related IDs in bulk `IN` queries and map into immutable read records.
