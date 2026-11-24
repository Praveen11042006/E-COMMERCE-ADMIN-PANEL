# Phase 13 — Deployment Readiness

**Status: PASS** (two verification items blocked — see Known limitations)

Date: 2026-11-23

---

## 1. Phase objective

Make the backend, frontend, container and CI configuration deployment-ready without
changing API contracts, frontend behaviour, or touching any database. Phase 13 adds no
automatic deployment and no production migration.

---

## 2. Initial findings (inspection before any change)

| Area | Finding |
|---|---|
| Actuator | `spring-boot-starter-actuator` was already in `pom.xml`, but nothing exposed or permitted it, and `SecurityConfig.anyRequest().authenticated()` rejected `/actuator/health` with 401. No management properties existed. |
| Hibernate schema | `application.properties` sets `ddl-auto=update`; `application-prod.properties` set no override, so the production profile also ran `update` — the opposite of the Phase 11 intent. |
| Prod profile | Existed and was correct otherwise: no fallback secrets, `ProductionConfigurationValidator` rejects blank/non-MySQL URL/non-HTTPS CORS origins, Flyway disabled. |
| DB connection | Env-driven (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`); no credentials in source. |
| Error handling | `GlobalExceptionHandler` returns controlled JSON and hides internals, but logged **nothing** — no logger in the backend at all. |
| Frontend build | `vite build` passes; base URL read from `VITE_API_BASE_URL` with a `localhost:8080` fallback (`||`, so an explicit empty value could not be honoured). |
| Frontend container | No `Dockerfile`, no nginx config, no `.dockerignore`. |
| Backend container | `Dockerfile` existed: multi-stage, but full JDK runtime, running as root, no health check. |
| Orchestration | No `docker-compose.yml`; no persistent volume, no health checks, no service ordering. |
| CI | `quality.yml` ran frontend tests/lint/build and backend tests, but `./mvnw` is committed with mode `100644`, so `./mvnw test` on `ubuntu-latest` would fail with "Permission denied". No timeouts, no run concurrency. |
| Secrets | Root `.gitignore` ignored `.env*`, which would also have hidden a `.env.example`. |

---

## 3. Files inspected

Backend: `pom.xml`, `Dockerfile`, `src/main/resources/application.properties`,
`application-prod.properties`, `application-flyway-test.properties`,
`db/migration/V1__baseline_schema.sql`, `META-INF/spring.factories`,
`config/SecurityConfig.java`, `config/ProductionConfigurationValidator.java`,
`config/FlywayTestSafetyGuard.java`, `exception/GlobalExceptionHandler.java`,
`model/*.java`, `controller/{Order,Product,Coupon,Payment,Authentication}Controller.java`,
`dto/AdminUserUpdateRequest.java`, `service/ProductService.java`,
`src/test/resources/application.properties`, `controller/SecurityHeadersAndCorsTest.java`.

Frontend: `package.json`, `vite.config.js`, `src/utils/api.js`, `index.html`,
`src/UserComponents/PlaceOrder.jsx`, `src/components/OrderDetails.jsx`,
`src/Dashboard/*.jsx`, `src/App.jsx`, `.gitignore`.

Infrastructure: `.github/workflows/quality.yml`, root `.gitignore`, `README.md`,
`DOCUMENTATION.md`, `PROJECT_REMAINING_PHASES_AUDIT.md`, phase reports 10A–12G.

---

## 4. Files modified / created

**Modified**

1. `springapp/src/main/resources/application.properties` — actuator exposure block
   (`health` only, details hidden, liveness/readiness probes enabled), `logging.level.root`,
   `server.forward-headers-strategy=framework`.
2. `springapp/src/main/resources/application-prod.properties` — `ddl-auto=validate`,
   actuator health detail suppression.
3. `springapp/src/main/java/.../config/SecurityConfig.java` — `GET /actuator/health[/**]` permitted.
4. `springapp/src/main/java/.../exception/GlobalExceptionHandler.java` — SLF4J logger;
   warns on 401/403/rejected-internal-detail, logs 5xx with stack trace server-side only.
5. `springapp/Dockerfile` — JRE runtime (was full JDK), non-root user, `curl` for health,
   `HEALTHCHECK` against `/actuator/health`, `JAVA_OPTS` passthrough via `exec`.
6. `springapp/src/test/resources/application.properties` — mirrors the actuator exposure
   (the test classpath file shadows the main one, so the config was otherwise untested).
7. `reactapp/src/utils/api.js` — `??` instead of `||` so an explicitly empty
   `VITE_API_BASE_URL` yields same-origin requests; dev fallback unchanged.
8. `.github/workflows/quality.yml` — `chmod +x mvnw` before use, `--batch-mode`,
   per-job `timeout-minutes`, run `concurrency`.
9. `README.md` — health endpoint, Docker workflow, API base URL, test commands.
10. `.gitignore` — `!.env.example` exception.

**Created**

11. `springapp/src/main/resources/application-docker.properties` — compose-only profile
    (schema bootstrap for an empty volume; explicitly not production).
12. `springapp/.dockerignore`, `reactapp/.dockerignore`.
13. `reactapp/Dockerfile` — node 22 build → `nginx:1.27-alpine`, health check.
14. `reactapp/nginx.conf` — SPA fallback, same-origin `/api` + `/auth` proxy (buffering off
    for the streaming CSV export), asset caching, hardening headers.
15. `docker-compose.yml` — MySQL 8 with named volume + health check, backend `depends_on`
    `service_healthy`, frontend gated on backend health, DB not published to the host,
    required secrets via `${VAR:?}`.
16. `.env.example` — placeholders only; no real credentials.
17. `springapp/src/test/java/.../controller/ActuatorHealthTest.java` — 4 tests.

No database was read, written, migrated or validated against MySQL. No Flyway command was
run. No API path, request/response shape or frontend screen was changed.

---

## 5. Tests executed (actual results)

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` (JDK 23, source/target 17) | **131 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** (127 pre-existing + 4 new) |
| `cd reactapp && npm run test:run` | **12 files, 30 tests passed** |
| `cd reactapp && npm run lint` | **exit 0 — 0 errors, 6 pre-existing warnings** |
| `cd reactapp && npm run build` | **exit 0** — `dist/index.html`, `index-*.js` 284.83 kB (gzip 94.42 kB) |
| `cd reactapp && VITE_API_BASE_URL= npm run build` | **exit 0** — 0 occurrences of `localhost:8080` in `dist/assets`, confirming the same-origin (nginx-proxied) build the Dockerfile performs |
| `docker compose config` (with dummy required vars) | **exit 0** — config interpolates and validates |
| `docker build ./reactapp` | **exit 1 — Docker daemon not running** ("failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine") |

The first run of the new actuator test exposed two real problems, both fixed rather than
weakened: the test classpath `application.properties` shadows the main file (so actuator
properties had to be mirrored in test resources), and `/actuator/env` is rejected with 401
by the security chain before it can 404. The test now asserts 401 for non-health actuator
paths and 200 for `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`.

---

## 6. Known limitations

1. **Docker images were not built or run** — the Docker daemon is not running on this
   machine. Only `docker compose config` (schema/interpolation) was validated. The compose
   stack, the nginx proxy and both health checks are therefore **unverified at runtime**.
2. **Production `ddl-auto=validate` has not been exercised against MySQL.** It is configured
   in `application-prod.properties`, but the only database tests use H2. This is the same
   Phase 11/14 blocker: no staging database and no verified backup.
3. The compose stack intentionally uses the `docker` profile (`ddl-auto=update`) so an empty
   volume can bootstrap. It is a demo stack; production behaviour is the `prod` profile.
4. `management.endpoint.health.probes.enabled=true` adds the probe groups; the readiness
   group includes the database indicator by default, so a DB outage makes readiness DOWN.
   This is intended but untested against MySQL.

---

## 7. Remaining risks

| Risk | Severity | Notes |
|---|---|---|
| Image build/runtime unverified | Medium | Run `docker compose up --build` on a machine with Docker Desktop to close this. |
| Prod schema validation unverified | High for go-live | Requires staging database + backup evidence (Phase 14). |
| JWT in `localStorage` | Medium | XSS exposure; nginx now serves a static SPA with no CSP (deliberately omitted to avoid breaking the bundle). |
| In-memory login rate limiter | Medium | Per-instance only; multi-replica deployments weaken it. |
| No metrics/tracing | Low | Only the health endpoint is published; no Prometheus/OTel. |
| `X-Forwarded-*` trusted unconditionally | Low | `forward-headers-strategy=framework` assumes a trusted proxy in front (nginx in compose). |

---

## 8. Follow-on project completion audit (Phase 13 read-only pass)

Evidence gathered from source inspection; features were traced to their implementation,
not inferred from file names.

### Implemented and wired end-to-end
- **Authentication/authorization**: login/register, BCrypt, JWT filter, admin-only admin
  creation, 12 `@PreAuthorize` sites plus URL rules, JSON 401/403, rate limiting, security
  headers, CORS allow-list. JWT is stateless; there is no logout/refresh endpoint (client
  deletes the token).
- **Product management**: admin CRUD with audit entries, public catalogue with
  category/price filters, product forms and modal editing in `ProductList`.
- **Cart/checkout**: `PlaceOrder` builds a client-side cart, blocks out-of-stock and
  over-quantity, applies coupons via `POST /api/coupons/validate`, and creates the order;
  stock is deducted only server-side inside one transaction.
- **Inventory**: server-side stock deduction, restore on cancellation/deletion,
  pessimistic locking (`findByIdForUpdate`), low-stock analytics, demand/reorder predictions.
- **Order management**: admin list with pagination/sort/filter/stats, detail view, status
  update, delete; customer list/detail/cancel of own orders (ownership from JWT).
- **Coupons**: server-side validation, usage limits, restore on cancel, admin
  list/create/delete API, coupon entry in checkout.
- **User management**: paged user list, detail/edit, delete, activate/deactivate via
  a strictly bounded `AdminUserUpdateRequest` that rejects unknown fields.
- **Analytics/ML**: summary dashboard plus RFM, forecast, demand, risk, recommendations.
- **CSV export**: streaming `StreamingResponseBody`, frontend blob download, nginx proxy
  configured not to buffer it.
- **Backend validation**: Bean Validation on order creation, order status, payment and
  admin user update; global handler maps violations to field-level 400s.
- **Security**: secrets env-only, production fail-fast validator, password never serialized,
  API responses never leak stack traces, actuator restricted to health.
- **Testing**: 131 backend tests (H2), 30 frontend tests across 12 files, CI on push/PR.

### Confirmed gaps (not yet addressed — proposed for Phase 14/15)
1. **Payment UI missing.** `payOrder` and `fetchInvoice` exist in `api.js` but no screen calls
   them; `OrderDetails` renders `order.paymentStatus`/`paymentMethod`, fields the `Order`
   entity does not have, so that block never renders. Payment is API-only (and mock-only).
2. **Coupon admin UI missing.** Coupon create/list/delete exist in the API; no admin screen.
3. **Coupon API validation is thin.** `CouponController.create` and product create/update
   accept entities without `@Valid`, and `Coupon`/`Product` carry no constraints, so a
   malformed coupon (blank code, negative discount) reaches the database and surfaces as a
   generic 400.
4. **Product listing is unpaginated** (`GET /api/products` returns every row); orders and
   users are paginated. Catalogue growth will degrade browse/checkout.
5. **Accessibility is limited.** `aria-*`/`alt` appear in 4 of 24 JSX files; modals in
   `PlaceOrder` are plain `div` overlays with no `role="dialog"`, focus trap or Escape
   handling.
6. **No end-to-end or browser test**; integration is covered by mocked-API component tests
   and MockMvc, so frontend↔backend contract drift can pass CI.
7. **Observability** has no metrics/structured logging beyond the new SLF4J warnings and
   errors, and no log shipping or alerting.
8. **Bundle size**: `AdminDashboard` chunk is 468 kB raw, no manual chunking.

None of these break existing behaviour; each is additive work.

---

## 9. Recommended next phase

**Phase 14 (BLOCKED pending prerequisites)** — production database adoption: staging
database, verified backup, restore rehearsal. Independent of that blocker, a **Phase 13B**
could close the runtime gaps found here: coupon/product request validation, coupon admin
screen, and wiring the payment/invoice flow into the UI, plus the product pagination gap.
