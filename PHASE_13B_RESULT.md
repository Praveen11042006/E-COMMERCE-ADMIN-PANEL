# Phase 13B — Application Quality and Feature Completion

**Status: PASS**

Date: 2026-11-23
Baseline before this phase: 131 backend tests, 30 frontend tests (12 files),
0 ESLint errors / 6 warnings, production build passing.

---

## 1. Phase objectives

1. Request validation for coupon and product writes (DTOs, Bean Validation, field-level errors).
2. Admin coupon management UI.
3. Payment/invoice UI integration using only real backend fields.
4. Server-side product pagination with the existing pagination conventions.

---

## 2. Initial findings

| Area | Finding |
|---|---|
| Product writes | `ProductController` bound the `Product` entity directly with **no bean validation**, and `ProductService.updateProduct` copied whatever it received. `create` had a business check, `update` had none: negative price/stock and blank names could be persisted via `PUT`. |
| Coupon writes | `CouponController.create` bound the `Coupon` entity with no `@Valid`; the service checked code/type/value but not negative `minOrderAmount`/`maxDiscount`/`usageLimit`, not percentage > 100, and not past expiry dates. |
| Validation errors | `GlobalExceptionHandler` already returns `{message, errors{field}}` for `MethodArgumentNotValidException`, so field-level reporting just needed annotations + `@Valid`. |
| Coupon UI | Backend list/create/delete existed; `api.js` had only `validateCoupon`. No admin screen, no route, no nav entry. |
| Payment UI | `payOrder`/`fetchInvoice` existed in `api.js` but were never called. `OrderDetails` rendered `order.paymentStatus`/`order.paymentMethod` — fields that do not exist on the `Order` entity. It also invented an 18% tax and ₹50 shipping that the backend does not model, and rendered dead `customerPhone`/`trackingNumber`/`notes` blocks. |
| Product catalogue | `GET /api/products` returns **every** row; the customer page fetched all of them and filtered in memory. Orders and users already use `PageRequestParams` + `PageResponse`, giving an established convention to follow. |
| Page limits | Existing conventions: `page` >= 0, `size` 1..100, sort field whitelist; violations return 400 via `ValidationException`. |

---

## 3. Files inspected

Backend: `ProductController`, `ProductService`, `ProductRepository`, `CouponController`,
`CouponService`, `CouponRepository`, `Coupon`, `Product`, `PaymentController`,
`PaymentService`, `Payment`, `Order`, `OrderController`, `PageRequestParams`,
`PageResponse`, `GlobalExceptionHandler`, `SecurityConfig`, `ProductControllerTest`,
`SecurityIntegrationTest`, `src/test/resources/application.properties`.

Frontend: `api.js`, `OrderDetails.jsx` (+ test), `UserProductList.jsx` (+ test),
`ProductList.jsx`, `PlaceOrder.jsx`, `CreateOrder.jsx`, `Analytics.jsx`,
`AdminDashboard.jsx`, `test/test-utils.jsx`.

---

## 4. Files modified / created

**Created**
- `springapp/.../dto/ProductRequest.java` — validated product request + `toProduct()`.
- `springapp/.../dto/CouponRequest.java` — validated coupon request + `toCoupon()`.
- `springapp/.../controller/ProductValidationTest.java` (10 tests).
- `springapp/.../controller/CouponValidationTest.java` (13 tests).
- `springapp/.../controller/ProductPaginationTest.java` (13 tests).
- `reactapp/src/components/CouponList.jsx`, `CouponList.test.jsx` (6 tests).

**Modified**
- `ProductController` — `@Valid @RequestBody ProductRequest` on create/update; new `GET /api/products/paged`.
- `ProductService` — shared `requireValidProduct` for create **and** update; `getProductsPage(...)`.
- `ProductRepository` — `searchPaged(...)` JPQL query with nullable filters + `Pageable`.
- `PageRequestParams` — `PRODUCT_SORT_FIELDS` + `parseProductParams(...)`.
- `CouponController` — `@Valid @RequestBody CouponRequest` on create.
- `ProductControllerTest` — the two invalid-create assertions now expect the central
  field-level body (`message: "Validation failed"`, `errors.<field>`) instead of the old
  generic `"Invalid product data"` string. Assertions were tightened, not removed; the
  valid-create response contract (201 + Product JSON) is unchanged.
- `reactapp/src/utils/api.js` — added `fetchProductsPaged`, `fetchCoupons`, `createCoupon`,
  `deleteCoupon`, `fetchPaymentsForOrder`.
- `reactapp/src/components/OrderDetails.jsx` — real payment/invoice section; removed
  non-existent order fields and invented tax/shipping; real discount/coupon totals.
- `reactapp/src/components/OrderDetails.test.jsx` — mocks extended, 5 payment/invoice tests added.
- `reactapp/src/UserComponents/UserProductList.jsx` — server-side paging/search/filter UI.
- `reactapp/src/UserComponents/UserProductList.test.jsx` — 6 tests for the paged contract.
- `reactapp/src/Dashboard/AdminDashboard.jsx` — Coupons nav item + `coupons` route.
- `reactapp/src/components/Analytics.test.jsx` — test-only robustness fix: the Total
  Orders assertion is scoped to its card instead of a bare `getByText('1')` (see §8).

No database, migration, secret, dependency or deployment file was touched.

---

## 5. Features implemented

### 5.1 Product validation
`ProductRequest`: `@NotBlank` + `@Size(255)` name/description, `@NotBlank`/`@Size(100)`
category, `@NotNull @DecimalMin("0.01") @Digits(10,2)` price, `@NotNull @PositiveOrZero
@Max(1000000)` stock, `@Size(500)` image URL. `ProductService.requireValidProduct` also
rejects negative stock on **update** (previously unguarded). Responses are unchanged.

### 5.2 Coupon validation
`CouponRequest`: code `@NotBlank/@Size(50)/@Pattern([A-Za-z0-9_-]+)`, discount type
`@Pattern(PERCENTAGE|FIXED)`, value `@NotNull @DecimalMin("0.01") @Digits(10,2)`,
non-negative `minOrderAmount`/`maxDiscount`/`usageLimit`, `@Future` expiry, plus
`@AssertTrue` rules: percentage <= 100 and `maxDiscount` only for percentage coupons.
Service rules (duplicate code, uppercase normalisation, forced activation, usedCount 0)
are unchanged.

### 5.3 Coupon administration UI
`/admin/coupons` lists code, discount, minimum order, usage, expiry; creates coupons with
client-side pre-checks and displays backend field errors; deletes with confirmation;
loading/success/error states match the existing dashboard styling. Uses the existing
JWT-intercepted Axios instance, so admin authorization is enforced by the backend.

### 5.4 Payment / invoice UI
`OrderDetails` now loads `GET /api/payments/order/{id}` (failure never hides the order)
and shows status (PENDING/FAILED/SUCCESS), method, amount, invoice number and transaction
reference from the stored `Payment` rows. "Pay now (mock)" appears only when no SUCCESS
payment exists and the order is not CANCELLED, calls `POST /api/payments`, and refreshes
the records. "View invoice" calls `GET /api/payments/invoice/{id}` and shows the
server-computed line items, subtotal, discount and total in an accessible modal.
The UI states explicitly that no real payment is processed. Fake `paymentStatus`,
`paymentMethod`, 18% tax, ₹50 shipping and dead order fields were removed.

### 5.5 Product pagination
New `GET /api/products/paged?page&size&sort&category&minPrice&maxPrice&search` returns the
shared `PageResponse<Product>` envelope. Filters are applied in SQL; `page`/`size`/`sort`
are validated by the existing `PageRequestParams` rules (size 1–100, sort whitelist
`id|name|price|category|stockQuantity`). The legacy `GET /api/products` array endpoint is
untouched. The customer catalogue consumes the paged endpoint with debounced search,
category and price filters, and Previous/Next controls showing
"Showing X–Y of N" and "Page P of T".

---

## 6. API changes

| Endpoint | Change |
|---|---|
| `POST /api/products`, `PUT /api/products/{id}` | Request body now validated (`ProductRequest`); success contract unchanged (201/200 + Product JSON); invalid body returns 400 `{message:"Validation failed", errors:{field}}`. |
| `POST /api/coupons` | Request body now validated (`CouponRequest`); success contract unchanged (201 + Coupon JSON); invalid body returns the same field-level 400. |
| `GET /api/products/paged` | **New** additive endpoint, `PageResponse<Product>`. |
| `GET /api/products` | Unchanged (still returns the full array). |
| `POST /api/payments`, `GET /api/payments/order/{id}`, `GET /api/payments/invoice/{id}` | Unchanged; now actually consumed by the UI. |

No existing endpoint was removed, renamed, or had its success payload changed.

---

## 7. Tests added

Backend (36 new):
- `ProductValidationTest` (10): valid create, blank name, zero/negative price, negative
  stock, missing category, >2 decimals, invalid product not persisted, update rejects
  negative price and leaves the row unchanged, valid update.
- `CouponValidationTest` (13): valid create/normalisation, blank code, illegal code chars,
  unknown type, non-positive value, percentage > 100, negative minimum, negative usage,
  past expiry, fixed+maxDiscount, duplicate code, customer forbidden, admin list/delete.
- `ProductPaginationTest` (13): envelope/size, last page flags, price sort, category filter,
  price range, name/description search, default size, size > 100, negative page, bad sort
  field, inverted range, negative price filter, legacy array endpoint intact.

Frontend (15 new): `CouponList` (6), `OrderDetails` payment/invoice (+5), `UserProductList`
paged/filter/pagination (+4, two existing tests preserved).

---

## 8. Test execution results (actual)

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` | **167 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** |
| `cd reactapp && npm run test:run` | **13 files, 45 tests passed** |
| `cd reactapp && npm run lint` | **exit 0 — 0 errors, 6 pre-existing warnings** |
| `cd reactapp && npm run build` | **exit 0** (admin chunk 483.72 kB raw / 131.47 kB gzip) |

Intermediate failures and their resolution (not hidden):
- `CouponValidationTest.blankCodeIsRejected` initially asserted the `@NotBlank` message,
  but a blank value also violates `@Pattern` and the error collector keeps the first
  violation. The assertion now checks the shared field + message prefix.
- `ProductPaginationTest.searchMatchesNameAndDescription` returned 0 rows because MockMvc
  left a literal space in the query string; the test now passes the term via `.param(...)`.
  The SQL query itself was correct (category/price/search-by-description passed).
- One `mvn test` invocation was cut off by a machine disconnect; the suite still completed
  (2:42) with the two failures above, which were then fixed and re-run green.
- On a later full frontend run, the pre-existing `Analytics > renders Total Orders stat
  card` test failed with "Found multiple elements with the text: 1": the Products and
  Customers cards legitimately also render `1` for this fixture, so the assertion depended
  on render timing (it passed in isolation). The assertion now scopes to the Total Orders
  card with `within(...)` — same expectation, deterministic. No test was removed.

---

## 9. Security considerations

- Field-level validation stops malformed values before JPA; the service retains its own
  checks so non-HTTP callers cannot bypass them.
- Coupon/product responses remain entity JSON with no new fields, so no internal data is
  exposed. `@JsonIgnore` on the `@AssertTrue` helpers prevents them entering responses.
- New admin UI calls only admin-guarded endpoints (server-side `hasRole('ADMIN')`); the
  client does not decide permissions.
- Payment actions use the existing ownership check (`ADMIN` or the order's `userId` from
  the JWT); the client never sends an amount, method is `MOCK`, and no gateway exists.
- The paged product endpoint is public like the array endpoint (catalogue data is public);
  `size` is capped at 100 and sort fields are whitelisted, preventing CPU/DB abuse.
- Search terms are bound parameters in JPQL (no string concatenation into SQL).
- Invoice data is rendered as text, not HTML.

---

## 10. Remaining limitations

1. **Admin product inventory, checkout (`PlaceOrder`) and order creation (`CreateOrder`)
   still call the legacy `/api/products` array endpoint.** Their KPI cards and cart rely on
   the full catalogue, so paginating them needs a separate stats endpoint; this was left
   out of scope rather than changing their behavior.
2. Frontend filtering is server-side only in the customer catalogue; the admin inventory
   keeps its in-memory filters.
3. Coupons are create/list/delete only — no edit endpoint exists in the backend, so the UI
   exposes no editing.
4. Payment remains a mock gateway (deterministic SUCCESS); no real processing is implied.
5. Payment/invoice UI lives in the admin order detail page; customer-facing order cards do
   not yet show payment state.
6. `OrderDetails` shows line-item subtotal and the stored discount; tax/shipping are not
   modelled by the backend and are no longer fabricated.
7. No browser/E2E test; component tests mock the API layer.
8. Docker images still cannot be built/run on this machine (Docker daemon not running) —
   unchanged from Phase 13.

---

## 11. Final status

**PASS** — all four objectives implemented, 167 backend and 45 frontend tests green,
lint clean (0 errors, 6 pre-existing warnings), production build passing, and the only
existing test changed was updated to assert the new, stricter field-level validation
contract.

---

# Addendum — review and pagination completion

Date: 2026-11-23 (same day, follow-up request)
**Status: PASS**

## A1. Review findings

Re-read the Phase 13B diff with fresh eyes:

- **Validation DTOs** hold only constraint annotations plus a single `toEntity`/`toCoupon`
  mapper; the service-level checks remain as the second gate. Nothing redundant to remove;
  the `@AssertTrue` rules encode real invariants (percentage <= 100, cap only meaningful for
  percentage), so they stay.
- **`ProductService`** had two copies of the product business rules after the update guard
  was added; both now delegate to one private `requireValidProduct`.
- **Leftover unused imports** (`Phone`, `TruckIcon`) in `OrderDetails` were removed while
  wiring the payment section.
- **Hook warnings**: `ProductList`, `PlaceOrder` and `CreateOrder` previously produced
  `react-hooks/exhaustive-deps` warnings. Their loaders are now `useCallback`-memoised, so
  the project lints with **0 errors and 0 warnings** (previously 6 warnings).
- **Test coverage check** confirmed the three requested paths were covered before this
  addendum: validation (23 backend tests), payment/invoice (5 frontend + `PaymentServiceTest`),
  pagination (13 backend + 6 catalogue frontend). Gaps were the un-paged admin/checkout
  screens; those are covered below.
- One pre-existing `Analytics` assertion was asserting nothing meaningful: it matched *any*
  "1" on the page (three different stat cards can show 1), and the Total Orders card itself
  renders after one extra tick. It now waits for and asserts the Total Orders card value.

## A2. Pagination completed for the remaining screens

**New backend endpoint** `GET /api/products/stats` → `ProductStatsDto`
(`totalProducts`, `inStock`, `outOfStock`, `lowStock`, `totalValue`) built from three
aggregate queries plus `count()`; no rows are loaded. The legacy array endpoint is still
untouched.

**Admin inventory (`ProductList`)** now loads 20 rows at a time from `/api/products/paged`
with server-side search, category filter and the six sort options; the KPI cards read the
new stats endpoint. Deleting the last row of the final page steps back one page. Editing
and deleting keep working against the current page and refresh both rows and stats.

**Checkout (`PlaceOrder`) and admin order creation (`CreateOrder`)** load 12 products per
page with server-side search/category/sort and Previous/Next controls. Because a cart must
survive page changes, both keep an accumulated `catalog` map (`id → product`) of every
product seen; cart lines, per-line totals, quantity limits and the order total resolve from
that map, so an item added on page 1 still prices correctly after moving to page 3.

**Behaviour preserved:** all filters and sort options, the cart, coupon validation,
order submission, payment/invoice hand-off, stock checks and the "Added ✓" state.
The category filter now matches anywhere in the category name (server `contains`) instead
of only the prefix (client `startsWith`) — a small, documented divergence.

## A3. Files changed in the addendum

- Backend: `ProductStatsDto` (new), `ProductRepository` (3 aggregates), `ProductService`
  (`getStats`, single validation helper), `ProductController` (`GET /stats`),
  `ProductPaginationTest` (+2 stats tests).
- Frontend: `api.js` (`fetchProductStats`), `ProductList.jsx`, `PlaceOrder.jsx`,
  `CreateOrder.jsx`; tests `ProductList.test.jsx` (new, 4), `CreateOrder.test.jsx`
  (new, 3), `PlaceOrder.test.jsx` (updated, +1 cross-page cart test), `Analytics.test.jsx`
  (timing-correct assertion).

## A4. Verification (actual)

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` | **169 tests, 0 failures, 0 errors — BUILD SUCCESS** |
| `cd reactapp && npm run test:run` | **15 files, 53 tests passed** |
| `cd reactapp && npm run lint` | **exit 0 — 0 errors, 0 warnings** |
| `cd reactapp && npm run build` | **exit 0** |

Intermediate failures fixed during this addendum: the `Analytics` timing/ambiguity issue
above, and two `PlaceOrder` assertions that assumed a product name appears once (it also
appears in the order summary) — changed to `getAllByText`.

## A5. Remaining limitation

`Analytics.jsx` still calls the legacy array endpoint: its revenue-by-category and
inventory charts aggregate over the whole catalogue, which would need a dedicated
aggregation endpoint before it can be paged. Everything else that browses products
(customer catalogue, admin inventory, checkout, admin order creation) is now paged.
