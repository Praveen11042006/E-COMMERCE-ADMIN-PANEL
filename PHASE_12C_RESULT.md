# Phase 12C — Analytics Query Optimization and CSV Export Scalability

## Original bottlenecks

- `OrderService.getOrderStats()` loaded every order together with every order item and product through `findAllWithItems()` to calculate scalar dashboard KPIs. The calculation needs only columns from `orders`.
- `GET /api/orders/export` selected up to 100,000 ids, loaded all matching order/item/product graphs, mapped all rows to DTOs, built one `String`, then copied it into one `byte[]` before responding.
- `AnalyticsService`, `DemandService`, `RecommendationService`, `RfmService`, `RiskService`, and the product/category analytics portions of `AnalyticsService` also use `findAllWithItems()`. Their current calculations inspect order items, product/category values, or per-customer order history, so they were not mechanically replaced with scalar aggregates.

## Files changed

- `springapp/src/main/java/com/examly/springapp/repository/OrderRepository.java`
- `springapp/src/main/java/com/examly/springapp/service/OrderService.java`
- `springapp/src/main/java/com/examly/springapp/controller/OrderController.java`
- `springapp/src/test/java/com/examly/springapp/service/OrderAnalyticsOptimizationTest.java`
- `springapp/src/test/java/com/examly/springapp/controller/PaginationApiTest.java`

No database schema, migration, V1 file, index, or production configuration was changed.

## Query changes

`OrderService.getOrderStats()` now delegates to one repository aggregate projection over `orders`:

- total order count;
- individual counts for PENDING, PROCESSING, SHIPPED, DELIVERED, and CANCELLED;
- non-cancelled revenue sum and count for average order value.

The query does not join `order_item` or `product`, so it cannot duplicate an order because of multiple items. It retains the preceding semantics: case-insensitive status comparisons; unknown/null statuses count in total and are included in revenue; cancelled orders are excluded from revenue and average order value; empty sets return zeros.

Verified from generated H2 SQL: one `select count/sum ... from orders` is issued for this KPI calculation, replacing the prior fetch join of orders, items, and products. This is a query-shape reduction, not a production benchmark.

## CSV export changes

`GET /api/orders/export` keeps its URL, filters, sorting, authorization, content type, filename pattern, CSV header, columns, order, values, and escaping rules. Its response body is now `StreamingResponseBody`.

The service writes the header and processes matching rows in ordered batches of 500 (up to the existing 100,000-row cap). Each batch performs the existing id-page lookup and one bounded fetch join for rows/items/products; it is written before the next batch is loaded. The endpoint no longer constructs a complete result DTO list, full CSV `String`, or full response `byte[]` in application memory. Writer closure is owned by try-with-resources; empty exports return the compatible header-only CSV.

Expected memory improvement: application-side export memory is bounded by a 500-order batch plus servlet/output buffers rather than 100,000 DTOs plus the entire CSV byte payload. This is architectural reasoning; no production-memory benchmark was run.

## Compatibility and authorization

- Order stats JSON DTO and calculation rules are unchanged.
- CSV header remains `Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count`.
- CSV quotation retains quoted text fields and doubles embedded quotes, preserving commas and newlines.
- `@PreAuthorize("hasRole('ADMIN')")` remains on stats and export. No authorization was weakened.

## Tests executed

Focused tests:

```powershell
.\mvnw.cmd "-Dtest=OrderAnalyticsOptimizationTest" test
.\mvnw.cmd "-Dtest=PaginationApiTest" test
```

- `OrderAnalyticsOptimizationTest`: 4 tests, 0 failures/errors/skips.
- `PaginationApiTest`: 18 tests, 0 failures/errors/skips in the final run.

Final full backend verification:

```powershell
.\mvnw.cmd test
```

Measured Surefire result: **127 tests across 21 reports; 0 failures, 0 errors, 0 skipped**.

All tests used the existing in-memory H2 test datasource with Flyway disabled. No MySQL, original ecom, production system, DDL/DML against an external database, or Flyway operation was used.

## Remaining risks and deferred optimizations

- Graph-dependent dashboard and ML features still load complete orders with items/products. They need item-level data or per-customer historical detail, so replacing them with a scalar aggregate would change behavior. Safe future work could add dedicated aggregate/projection queries per metric after comparison tests are expanded.
- Offset pagination has the normal deep-page database cost. Keyset pagination could improve very deep CSV exports, but would require a separately reviewed ordering/continuation-token contract.
- The aggregate query was verified on H2 only. Production query plans and real-world throughput need database-specific benchmarks before performance claims are made.
