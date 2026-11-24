# PHASE 13D RESULT — Analytics Aggregation & Customer Payment Visibility

Date: 2026-11-23
Baseline: 169 backend tests, 53 frontend tests (15 files), ESLint 0/0, build passing.
**Final status: PASS** — 180 backend tests, 62 frontend tests (16 files), ESLint 0/0, build passing.

No database schema, SQL, Flyway, secrets or dependency changes. No destructive Git commands.
All pre-existing pagination and functionality preserved.

---

## 1. Analytics aggregation

### Problem
`Analytics.jsx` downloaded the entire product catalogue through `GET /api/products` just to
compute two widgets: the product count and the low-stock list. It also aggregated the
category-revenue pie in the browser from every order item.

### Backend additions
| Endpoint | Query | Returns |
|---|---|---|
| `GET /api/analytics/inventory` | `count()` + `stockQuantity < 20` ordered ascending, `LIMIT 5` | `{ totalProducts, lowStockProducts[5] }` |
| `GET /api/analytics/category-sales?from=&to=` | `sum(priceAtPurchase * quantity)` grouped by category | `[{ category, revenue, quantity }]` sorted by revenue desc |

- New repository projection `OrderItemRepository.CategorySalesRow` with a single
  `@Query`; only aggregates leave the database, no product/order entities are loaded.
- `AnalyticsService.getInventory()` materialises at most five products and uses a COUNT
  for the total; the alert threshold (20) and limit (5) match the widget's previous
  client-side filter exactly.
- `from`/`to` are optional epoch-millisecond bounds converted to the server zone and
  mapped onto `orderDate`; omitting them aggregates the whole history (the "All Time"
  filter). Cancelled orders are included, exactly as the previous chart did.
- Both endpoints sit under `/api/analytics/**`, which `SecurityConfig` already restricts
  to `ADMIN`.

### Frontend changes (`Analytics.jsx`)
- `fetchProducts()` and the `products` state are gone.
- `loadData()` now fetches `fetchInventory()` + `fetchOrders()`.
- `loadCategorySales()` runs on mount and whenever the date filter changes, sending the
  same date window the client filter uses (extracted into `getFilterDateRange()` and
  reused by `filterOrdersByDate()`, so the two cannot drift).
- `stats.categoryData` is built from the aggregated rows (sorted, coloured);
  `stats.totalProducts` / `stats.lowStockProducts` come from the inventory endpoint.
- The "Today" 30-second polling refreshes orders, inventory and category sales, with
  memoised loaders so the effect does not re-subscribe on every render.

### Tests
- Backend `AnalyticsAggregationTest` (4): category grouping/revenue/quantity, date-window
  filtering, inventory count + `LIMIT 5` low-stock projection, admin-only + 401/403.
- Frontend `Analytics.test.jsx`: mock switched to `fetchInventory`/`fetchCategorySales`;
  new assertions for the low-stock widget, the no-window category request, and the
  date-window request after selecting a filter.

---

## 2. Customer payment visibility

### Backend
- New `PaymentSummaryDto` (status, method, amount, transactionRef, invoiceNumber,
  createdAt) embedded as `payment` on `OrderListItemDto` — `null` when the order has no
  payment row.
- `OrderService.getOrdersPaged()` loads all payments for the page's order ids in ONE
  `findByOrderIdIn` query and keeps the newest record per order (timestamp, id tiebreak).
  The CSV export passes `null` so it does not add payment queries and its columns are unchanged.
- Ownership was already enforced by `PaymentController` (JWT `userId` / role, never a
  request parameter) and by `/api/orders/my/**` (JWT-scoped `findIdsPaged`); this phase
  adds regression coverage for the read path.

### Frontend
- `UserOrderItem` shows a payment badge next to the order status:
  `Payment: SUCCESS` (green), `PENDING` (yellow), `FAILED` (red), or
  `No payment recorded` (grey) when `payment` is null.
- The expanded details section renders only stored fields: status, method, amount,
  recorded timestamp, transaction reference and invoice number (when present), with an
  explicit message when no payment exists.
- No new API calls: the values arrive with the already-paged order row.

### Tests
- `PaymentVisibilityTest` (3): latest SUCCESS wins over an older FAILED attempt and all
  fields match the stored row; an order without a payment serialises `payment: null`;
  Bob's page never contains Alice's orders/references; `/api/payments/order/{id}` returns
  200 for the owner, 403 for another customer, 401 unauthenticated, 200 for ADMIN.
- `UserOrderItem.test.jsx` (6): SUCCESS/PENDING/FAILED badges, missing-payment badge,
  stored details, missing-payment details.

---

## 3. Coupon editing (implemented)

Safe to add, because the edit contract excludes the sensitive fields.

- `PUT /api/coupons/{id}` (ADMIN) with the existing validated `CouponRequest`.
- `CouponService.update()` copies only code, type, value, minimum order, cap, expiry and
  usage limit. **`usedCount` and `active` are preserved** — redemption history cannot be
  reset and a retired coupon cannot be silently revived by an edit.
- Duplicate codes (case-insensitive, excluding the coupon itself) are rejected;
  percentage > 100, non-positive value, negative amounts and a past expiry are rejected
  by the same bean-validation rules used on create.
- `SecurityConfig` now explicitly maps `PUT /api/coupons/**` to `ADMIN` (previously only
  POST and DELETE were listed, so a new PUT would have fallen through to
  "authenticated").
- Admin UI: `CouponList` gains an Edit action that loads the coupon into the form,
  switches the form to "Edit coupon CODE", submits via `updateCoupon`, and offers
  "Cancel edit". The usage counter is displayed in the table but never sent.
- Tests: `CouponUpdateTest` (4) — successful edit with preserved `usedCount`/`active`,
  duplicate code, invalid percentage and past expiry, customer 403 / anonymous 401;
  frontend `CouponList.test.jsx` gains an edit-flow test asserting the payload contains no
  `usedCount`.

---

## 4. Security checks
- Analytics endpoints: ADMIN only (verified 200 admin, 403 user, 401 anonymous).
- Payments: ownership from the JWT; another customer gets 403; no other customer's
  transaction reference or invoice number appears in a customer's order page.
- Order list DTO still omits `userId`; payment data is a projection of stored rows, and
  the client never supplies a user id or payment state.
- Coupon edit: ADMIN only at both URL and method level; `usedCount`/`active` immune to
  client input.
- No secrets added; no new dependencies.

---

## 5. Verification (actual)

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` | **180 tests, 0 failures, 0 errors — BUILD SUCCESS** (baseline 169, +11) |
| `cd reactapp && npm run test:run` | **16 files, 62 tests passed** (baseline 53, +9) |
| `cd reactapp && npm run lint` | **exit 0 — 0 errors, 0 warnings** |
| `cd reactapp && npm run build` | **exit 0** |

Intermediate failure fixed: the coupon percentage test initially asserted the wrong
bean-validation key (`discountValue` instead of `percentageDiscountWithinRange`), and one
payment-detail assertion was ambiguous because `₹300` also appears in the line-item table
— both assertions were corrected, not weakened.

---

## 6. Limitations
- The category endpoint rounds revenue to 2 decimals (the old client summed raw doubles)
  and, when a date window is supplied, excludes rows with a null `orderDate` (orders are
  always created with a timestamp, so this is theoretical).
- `Analytics.jsx` still loads all orders through the legacy `GET /api/orders` for the
  other charts; only the product-catalogue dependency was removed in this phase.
- Coupon edit cannot change `active` or `usedCount` by design; editing a coupon whose
  expiry is in the past requires choosing a future expiry (the create-time rule).
- Payments remain mock: `PaymentService` writes a PENDING row and immediately marks it
  SUCCESS with a `MOCK-` reference. No gateway, credential or checkout transaction logic
  was changed. PENDING/FAILED states are rendered correctly when present in stored data.
- No browser visual check was performed; UI changes were verified through component tests
  and the production build only.

---

## 7. Review addendum — fresh-eyes pass

Date: 2026-11-23. **Status: PASS** — 183 backend tests, 62 frontend tests, ESLint 0/0, build passing.

### Findings and actions
1. **Analytics category race (fixed).** The category request is filter-dependent, so two
   quick filter changes could let an older response overwrite a newer one. `loadCategorySales`
   now stamps each request with a monotonically increasing `useRef` id and drops stale
   responses (both success and error paths). Five lines, no behaviour change otherwise.
2. **Fully-qualified type in `AnalyticsController` (simplified).** `java.util.List` is now an
   import, matching the `Map` import beside it.
3. **Payment batch query — reviewed, no change needed.** It issues exactly one
   `findByOrderIdIn` per page, short-circuits for an empty page, is bounded by
   `PageRequestParams.MAX_SIZE` (100) so the `IN` list cannot grow unbounded, and picks the
   newest record per order with a timestamp/id tiebreak (null timestamps cannot win by
   accident). The CSV export passes `null` instead of running the query at all.
4. **Analytics aggregation — reviewed, two edge cases now locked by tests.** Empty dataset
   (zero products/orders) returns `totalProducts: 0`, empty alerts and an empty category list;
   cancelled orders remain inside the category chart because that is exactly what the old
   client-side chart did.
5. **Admin list / CSV export — confirmed unchanged** by a new test: admin `/api/orders/paged`
   still returns the same envelope and additionally carries `payment`; the streamed CSV keeps
   the exact original header, one line per order, and contains no payment references; the
   legacy `GET /api/orders` array endpoint is untouched.

### Deliberate non-changes (observed, out of scope)
- The category chart includes revenue from cancelled orders (pre-13D behaviour, preserved on
  request) while `/api/analytics/summary` excludes cancelled revenue. The dashboard does not
  read `summary.categorySales`, so no inconsistency is visible; aligning the two would change
  displayed numbers and was left alone.
- Two low-stock thresholds coexist by design: the analytics alert uses `< 20`, the product
  stats endpoint uses `<= 10`. Each matches its own widget.
- `OrderService.getOrdersForExport()` and `buildOrdersCsv()` are pre-existing (Phase 10C) and
  unused; the live export streams via `writeOrdersCsv`. Left untouched as unrelated dead code.
- `/api/orders/my` (legacy, non-paged) has no payment field, but no screen uses it — the
  order history uses `/api/orders/my/paged`.

### Verification after the review pass

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` | **183 tests, 0 failures, 0 errors — BUILD SUCCESS** (+3 review tests) |
| `cd reactapp && npm run test:run` | **16 files, 62 tests passed** |
| `cd reactapp && npm run lint` | **exit 0 — 0 errors, 0 warnings** |
| `cd reactapp && npm run build` | **exit 0** |

Tests added in this pass: cancelled-order inclusion, empty-dataset aggregates, and the
admin-list/CSV-contract regression test. No production behaviour changed apart from the
stale-response guard.
