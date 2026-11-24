# PHASE 13E RESULT — Analytics Optimization & Browser E2E QA

Date: 2026-11-23
Baseline at phase start: 183 backend tests, 62 frontend tests (16 files), ESLint 0/0, build passing.
**Final status: PASS** — 188 backend tests, 62 frontend tests, ESLint 0/0, build passing, **7 Playwright browser tests executed and passing**.

---

## 1. Files changed

### Backend
| File | Change |
|---|---|
| `repository/OrderRepository.java` | 7 read-only aggregate queries + 6 projection interfaces (totals, status, top products, top customers, customer order counts, recent orders, time series). |
| `service/AnalyticsService.java` | `getOrderMetrics(from,to,days)` and `getTimeSeries(from,to)`; growth-rate window; no entity loads. |
| `controller/AnalyticsController.java` | `GET /api/analytics/order-metrics`, `GET /api/analytics/time-series`. |
| `test/.../AnalyticsDashboardMetricsTest.java` | 5 new tests (totals, tables, projection shape, window, admin-only). |

### Frontend
| File | Change |
|---|---|
| `components/Analytics.jsx` | No longer calls `GET /api/orders`; consumes the two new endpoints. Dashboard loader with stale-response guard; `filterOrdersByDate` removed (server now windows); stats memo rebuilt from aggregates; time-series memos keep the exact previous bucketing. |
| `utils/api.js` | `fetchOrderMetrics`, `fetchTimeSeries`. |
| `components/Analytics.test.jsx` | Rewritten for the new data sources + a stale-response regression test (6 tests). |
| `vite.config.js` | Vitest `include` limited to `src/**/*.test.*` so Playwright specs are not collected by Vitest. |
| `package.json` | `test:e2e` script; `@playwright/test` devDependency. |
| `playwright.config.js`, `e2e/*`, `e2e/start-backend.mjs` | Browser suite, login helpers, isolated H2 backend launcher. |
| `.gitignore` | Playwright artifacts. |

### E2E support (test scope only)
| File | Purpose |
|---|---|
| `springapp/src/test/resources/application-e2e.properties` | H2 in-memory datasource, port 8080, test JWT secret, CORS for `localhost:5173`. |
| `springapp/src/test/java/.../e2e/E2ESeedData.java` | `@Profile("e2e")` seed: admin, customer, 14 products, one unpaid historical order. |

---

## 2. Analytics endpoints and query efficiency

`GET /api/analytics/order-metrics?from=&to=&days=`
- totals: `sum(totalAmount)`, `count(*)`, `count(distinct customerEmail)` in one query;
- growth rate: second totals query over the previous equally long window (`days` only used here);
- status chart: `group by upper(status)`;
- top products: `sum(priceAtPurchase*quantity)` grouped by product id/name/category, ordered, `LIMIT 8`;
- top customers: `sum`, `count`, `min/max(orderDate)` grouped by email, `LIMIT 8`;
- repeat customers: one grouped customer-count query (scalar pairs, no entities);
- recent orders: `left join` items with `count`, `LIMIT 5`.

`GET /api/analytics/time-series?from=&to=`
- selects only `orderDate`, `totalAmount`, `customerEmail` — no order/product entities and no item arrays.

Previously the dashboard downloaded **every order with every item and product**. Now a refresh issues one bounded aggregate query per widget (plus the existing Phase 13D category-sales and inventory queries), with top lists limited in SQL. `GET /api/orders` is no longer called by any analytics screen.

**Why the two time-series charts are not SQL-aggregated:** their buckets are derived from the *browser's* timezone (`getUTCHours`, `toISOString`). Aggregating by server-local date/hour would silently move orders across bucket boundaries for users outside the server zone, changing the displayed trend/heatmap. The projection keeps the payload at three scalars per order while preserving the exact previous calculations; everything that does not depend on timezone is aggregated in the database.

Filters, labels, the date window, cancelled-order inclusion, KPI formulas, table limits (8/8/5) and empty-state behaviour are unchanged. Both new endpoints sit under the admin-only `/api/analytics/**` rule.

---

## 3. E2E tool choice and setup

Inspected the repo first: no E2E framework existed (Vitest + Testing Library only, jsdom). Selected **Playwright** (`@playwright/test` + Chromium) — the smallest standard browser-level option, no framework migration.

- `playwright.config.js` starts/reuses the Vite dev server (`:5173`) and the backend (`:8080`).
- `e2e/start-backend.mjs` compiles test classes, builds an explicit TEST classpath (`dependency:build-classpath`) and launches the app with `-Dspring.profiles.active=e2e --server.port=8080`.
- The `e2e` profile uses **in-memory H2** (`jdbc:h2:mem:e2e`) with `ddl-auto=create`; the MySQL configuration is not used. Seed users: `admin@e2e.test` / `Admin@12345`, `customer@e2e.test` / `Customer@12345`.

## 4. E2E scenarios actually executed

Command: `cd reactapp && npm run test:e2e` → **7 passed (18.5s)**, exit 0.

1. Unauthenticated `/admin/products` → redirected to `/login`.
2. Invalid password → inline error, stays on `/login`.
3. Customer logged in cannot enter `/admin/*` → redirected to `/user`.
4. Customer browses the catalogue; page 2 requires **Next** and shows the 13th product (12-per-page server pagination).
5. Full checkout: add product → customer details → **Place Order** → **Pay Now** (mock gateway) → **View My Orders**, where the paid order shows `Payment: SUCCESS` and the seeded unpaid order shows `No payment recorded`.
6. Admin creates a coupon through the form, sees the row, edits it, and the table shows the updated 15% discount with an "updated successfully" message.
7. Admin analytics dashboard renders aggregated KPIs and Top Products with no "Failed to load data" error.

The suite runs against the isolated H2 instance only; no production database, no SQL scripts, no Flyway.

---

## 5. Verification (actual, this phase)

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` | **188 tests, 0 failures, 0 errors — BUILD SUCCESS** (baseline 183, +5) |
| `cd reactapp && npm run test:run` | **16 files, 62 tests passed** |
| `cd reactapp && npm run lint` | **exit 0 — 0 errors, 0 warnings** |
| `cd reactapp && npm run build` | **exit 0** |
| `cd reactapp && npm run test:e2e` | **7 passed, 0 failed** (exit 0) |

Regressions caught and fixed while wiring the suite:
- Vitest initially collected the Playwright specs (`e2e/*.spec.js`) → Vitest `include` restricted to `src/**/*.test.*`.
- Two Playwright locators were ambiguous (two "Coupons" headings; product name also appears in its description) → scoped to headings/exact names. No app behaviour was changed to satisfy a test.

---

## 6. Security and regression checks
- `order-metrics` / `time-series`: admin 200, customer 403, anonymous 401 (asserted).
- Responses contain only scalar aggregates; no `Order`/`Product` entities, no `userId`, no other customers' rows.
- Category/inventory endpoints (Phase 13D) unchanged and still admin-only.
- Customer order history payment projection, coupon ADMIN-only PUT, and CSV export contract tests still pass.
- E2E profile cannot connect to MySQL: datasource URL is H2 in a test-resource properties file; the JWT secret is a test-only value; the seed component is inactive unless the `e2e` profile is enabled.
- No schema, Flyway, SQL, secret or payment-logic changes; no existing test removed or weakened.

---

## 7. Limitations, incidents and blockers

**Incident (reported, contained):** the first backend startup attempt used `spring-boot:run` with `-Dspring-boot.run.useTestClasspath=true`, which did **not** put the test resources/classes on the runtime classpath. The app therefore started with the normal configuration and connected to the local dev MySQL (`jdbc:mysql://localhost:3306/ecom`, `ddl-auto=update`) for about two minutes before being stopped. The `e2e` seed component was not loaded, so this suite inserted no data there; Hibernate's schema-update check may have run, and no SQL was executed manually. The launcher was replaced with `e2e/start-backend.mjs`, which builds an explicit test classpath (H2 verified: `url=jdbc:h2:mem:e2e` in the final runs).

- E2E tests mutate the in-memory database; they are written to tolerate repeat runs (unique coupon codes, id-independent assertions) but are intended for a freshly started backend.
- Time-series still transfers one row per order in the selected window (three scalars). It is a projection, not an aggregate, for the timezone reason above; very large windows will return many small rows.
- Status counts normalise to upper case, so mixed-case legacy statuses merge into one bar (previously two).
- Top customers use `max(customerName)` per email; a customer who changes their name shows the alphabetically greatest name.
- The E2E suite asserts visibility/text, not pixel layout; no screenshots were compared.
- Docker-image and production-schema checks from Phase 13 remain out of scope and unresolved.

**Final status: PASS** — every required command ran and passed, including 7 browser tests against an isolated H2 backend.
