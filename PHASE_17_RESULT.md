# PHASE 17 — STAGING READINESS & RELEASE RE-AUDIT

**Date:** 24 November 2026
**Project:** `E:\Subjects\ecomAlmostdone\ecomAlmostdone`
**Mode:** READ-ONLY (no file edits, no SQL, no MySQL connection, no Flyway, no container start/modify, no credential exposure)
**Overall status:** **BLOCKED** — the application itself is in good shape and every local automated check passes, but the reviewed code is **not the committed code**, there is **no staging environment**, and **database adoption (Phase 14) is still BLOCKED**.

This report does not claim production readiness. It re-audits staging readiness after Phases 13F, 15 and 16 and records what is proven, what is not, and what must happen before a staging release.

---

## 1. Executive summary

| Dimension | Verdict |
|---|---|
| Architecture & profile separation | **PASS** — base/prod/dev/docker/test/e2e are cleanly separated; base profile has no implicit database and never mutates schema |
| Authentication / authorization / JWT / CORS / CSP / rate limiting / errors | **PASS for demo scope** — no Critical or High application defect; four accepted Medium risks carried forward |
| Payment, checkout, coupons, orders, inventory, admin workflows | **PASS** (H2 + static review) — locking, transactions, server-side totals and ownership checks are correct |
| Pagination, analytics, CSV export, query performance | **PASS with gaps** — DB-side aggregates and streamed CSV; legacy whole-table endpoints remain |
| Docker Compose / Dockerfiles / nginx / health checks | **PASS (prior runtime evidence)** — no container was started in this phase; Phase 13F's runtime evidence stands |
| Deployment documentation | **PARTIAL / FAIL** — README is accurate; `DOCUMENTATION.md` is stale and contradictory |
| Automated tests & build | **PASS** — 229 backend + 66 frontend + 7 E2E, lint clean, build clean, npm audit 0 (all re-run in this phase) |
| CI status | **FAIL / NOT VERIFIED** — the only workflow is untracked at HEAD, so it has never run on the reviewed tree; no remote run can be verified |
| Source-control release state | **FAIL** — 49 modified + 129 untracked entries; HEAD contains 76 files and none of the production configuration |
| Staging infrastructure | **BLOCKED** — no staging environment, host, database, credentials, TLS termination or rollback plan |
| Production release | **BLOCKED** — Phase 14 (verified backup/restore + staging DB) and the source-control/CI gaps above |

**Bottom line:** the *code* is staging-quality for its documented demo scope. The *release* is not, because it cannot be reproduced from the repository, has never been validated by CI in its current form, and has no verified database or staging target.

---

## 2. Files and configurations inspected

### 2.1 Backend (68 Java files under `springapp/src/main/java`, 36 test classes under `src/test`)
- Config: `SecurityConfig`, `CorsConfig`, `ProductionConfigurationValidator`, `FlywayTestSafetyGuard`
- Security utilities: `JwtAuthFilter`, `JwtUtil`, `JwtSecretValidator`, `LoginRateLimiter`
- Controllers: `Authentication`, `Order`, `Payment`, `Product`, `User`, `Coupon`, `Analytics`, `Audit`, `Demand`, `Forecast`, `Recommendation`, `Rfm`, `Risk`
- Services: `OrderService` (checkout, paging, CSV, cancel/restore), `PaymentService`, `CouponService`, `ProductService`, `UserService`, `AnalyticsService`, ML services
- Repositories: `OrderRepository`, `OrderItemRepository`, `ProductRepository`, `PaymentRepository`, `CouponRepository`, `UserRepository`, `AuditLogRepository`
- DTOs incl. `PageRequestParams` (page/size/sort/filter validation), `OrderCreateRequest`, `PageResponse`
- `GlobalExceptionHandler`, entities (`Order`, `OrderItem`, `Product`, `Payment`, `Coupon`, `User`, `AuditLog`)
- Manifests/config: `springapp/pom.xml`, `Dockerfile`, `.dockerignore`, `.mvn/wrapper/maven-wrapper.properties`
- Profiles: `application.properties`, `application-prod.properties`, `application-dev.properties`, `application-docker.properties`, `application-flyway-test.properties`, `src/test/resources/application.properties`, `src/test/resources/application-e2e.properties`
- Migration: `src/main/resources/db/migration/V1__baseline_schema.sql` (7 tables, all `utf8mb4_0900_ai_ci`, all InnoDB)

### 2.2 Frontend (41 JS/JSX files under `reactapp/src`, 16 test files, 2 E2E specs)
- `App.jsx` (lazy dashboards, route guards), `components/PrivateRoute.jsx`, `utils/api.js` (axios interceptors)
- Pages: `Login`, `Register`, `AdminDashboard`, `UserDashboard`, `ProductList`/`ProductForm`, `OrderList`/`OrderDetails`/`CreateOrder`, `UserList`/`UserDetails`/`Profile`, `CouponList`, `Analytics`, `Insights`, `PlaceOrder`, `UserProductList`, `UserOrderList`, `UserOrderItem`
- Build/config: `package.json`, `package-lock.json`, `vite.config.js`, `eslint.config.js`, `playwright.config.js`, `index.html`, `tailwind.config.js`, `postcss.config.js`, `.env.development`
- Delivery: `reactapp/Dockerfile`, `reactapp/nginx.conf`, `.dockerignore`

### 2.3 Infrastructure and delivery
- `docker-compose.yml`, `.env.example`, `.gitignore`, `.github/workflows/quality.yml`
- `README.md`, `DOCUMENTATION.md`, `PROJECT_REMAINING_PHASES_AUDIT.md`
- Prior reports: `PHASE_13F_RESULT.md`, `PHASE_14_RESULT.md`, `PHASE_14A_RESULT.md`, `PHASE_14B_PREFLIGHT_RESULT.md`, `PHASE_14C_RESULT.md`, `PHASE_15_RESULT.md`, `PHASE_16_RESULT.md`
- Environment: Docker client/compose availability, disk free space, residual Phase 13F images/volume, listening ports, Git history/status/remotes

**Not inspected / not touched:** the live MySQL `ecom` database (no connection, no SQL), Docker daemon state changes, Flyway, any credential store.

---

## 3. Findings by severity

Every finding is classified **PASS**, **FAIL**, **BLOCKED** or **NOT VERIFIED**.

### 3.1 Critical

**None found.** No credential exposure, no authentication bypass, no authorization hole, no destructive query path in the reviewed code.

### 3.2 High

| ID | Finding | Class |
|---|---|---|
| **H1** | **The reviewed tree is not the committed tree.** `HEAD` (`e29fab9`, 5 commits total) contains **76 files**. The working tree has **49 modified** and **129 untracked** entries — including `application-prod.properties`, `application-dev.properties`, `application-docker.properties`, `application-flyway-test.properties`, `docker-compose.yml`, `reactapp/Dockerfile`, `reactapp/nginx.conf`, `.github/workflows/quality.yml`, `.env.example`, `.gitignore`, 26 backend test files and 19 frontend files. A clone of `origin/main` would have **no production profile, no compose stack, no nginx config, no CI workflow and no `.gitignore`**. | **FAIL** |
| **H2** | **No staging environment exists.** There is no staging host, no staging database, no staging secrets, no TLS termination, no deployment job and no rollback plan. Everything verified so far is local. | **BLOCKED** |
| **H3** | **Database adoption is still BLOCKED (Phase 14).** No verified data-bearing backup, no SHA-256 evidence, no restore rehearsal, no least-privilege backup account, no staging DB. Phase 14B/14C were read-only assessments; Phase 14C could not execute any SQL at all. | **BLOCKED** |
| **H4** | **CI has never validated this configuration.** The only workflow (`quality.yml`) is untracked, so GitHub Actions has nothing to run for it; `origin` exists but no remote run, badge or log was available for inspection. Green local suites are the only evidence. | **NOT VERIFIED** |

### 3.3 Medium

| ID | Finding | Class |
|---|---|---|
| **M1** | JWT is persisted in `localStorage` (`utils/api.js`), readable by any XSS; no XSS sink was found, but the storage choice is the weakest link. | **FAIL** (accepted for demo) |
| **M2** | No token revocation: logout is client-side only; `JwtAuthFilter` trusts the `role` claim without re-reading the user, so a deactivated or demoted account keeps its old authority for up to the 1-hour expiry. | **FAIL** (accepted for demo) |
| **M3** | Login rate limiting is **per application instance** (in-memory, bounded at 10 000 keys). Behind more than one replica or a load balancer the effective threshold multiplies. Fine for single-instance staging, unsafe for scale-out. | **FAIL** (documented) |
| **M4** | The payment provider is a **mock that always succeeds** (`PaymentService` sets `SUCCESS` unconditionally after the guards). Correct for a demo; a staging demo must be labelled as such, and it must never be presented as a payment integration. | **PASS as mock / FAIL as real payment** |
| **M5** | **No dependency-vulnerability scanning** in CI or the build: no OWASP `dependency-check` plugin in `pom.xml`, no `npm audit` step in the workflow, no Dependabot/Renovate config. Frontend is clean today (verified), backend CVE status is unassessed. | **NOT VERIFIED** (backend) / **FAIL** (process) |
| **M6** | **Documentation drift.** `DOCUMENTATION.md` §17 still claims "Product listing is not server-paginated" (it is, since Phase 13B) and "UI exhaustive-deps lint warnings remain" (lint is clean); §15 lists only 5 test suites, omitting ~15 (CSV export, pagination, rate limiter, safe DB config, password policy, exception handler, stats authorization, concurrency). A reviewer trusting this file gets a wrong picture. | **FAIL** |
| **M7** | **CI scope is too narrow to certify a release:** it runs frontend test/lint/build and backend `./mvnw test`, but no E2E, no image build, no `docker compose config`, no MySQL integration run, no dependency audit and no deploy step. | **FAIL** |
| **M8** | **Compose disables MySQL transport security** (`useSSL=false&allowPublicKeyRetrieval=true`). Acceptable on the private compose network for the demo; must not be carried into staging/production. | **PASS (demo) / FAIL (prod parity)** |
| **M9** | **No TLS anywhere in the stack:** nginx listens on port 80 only, compose publishes plain HTTP, and there is no certificate/ACME/ingress configuration or documentation. Spring's HSTS header is emitted only on HTTPS requests, so over plain HTTP it never applies. Staging/production require a TLS-terminating front end that does not exist yet. | **FAIL** (`BLOCKED` for staging) |
| **M10** | **CDN/proxy header trust:** `server.forward-headers-strategy=framework` is enabled globally, so `X-Forwarded-Proto/Host/Port` from any client are honoured. Safe only while the backend is unreachable except through nginx (true today: no published backend port). | **PASS (current topology) / risk if exposed** |

### 3.4 Low / informational

| ID | Finding | Class |
|---|---|---|
| L1 | Residual Phase 13F Docker resources still present: images `ecom13f-verify-backend:latest` (684 MB), `ecom13f-verify-frontend:latest` (74.8 MB), `mysql:8.0` (1.1 GB), volume `ecom13f-verify_mysql_data`; no containers. They hold only synthetic verification data. | **PASS** (known, nothing removed) |
| L2 | `C:` is 95 % full (11 GB free of 201 GB) — Docker data disk is on `C:`, so a staging container run has thin headroom (10 GB floor met, 15 GB preference not met). `D:` has 99 GB, `E:` 85 GB. | **PASS with warning** |
| L3 | Legacy whole-table endpoints remain: `GET /api/products` (`findAll`), `GET /api/orders` (admin, fetch-joined), `GET /api/users` (admin, returns entities), `GET /api/analytics/summary` (`findAllWithItems` + in-memory aggregation), `CouponService` `findAll`, `DemandService`/`RecommendationService` `findAll`. All are admin-only except the public product list; at catalogue scale they are full-table reads. | **PASS (scope) / risk at scale** |
| L4 | `getOrdersForExport` requests a page of up to `EXPORT_MAX_ROWS = 100_000` ids in one `Pageable` (legacy in-memory path); the streaming path is correctly batched at 500. | **PASS (bounded)** |
| L5 | The 100 000-row CSV cap has no test (documented in Phase 16); export omits payment columns by design. | **PASS / NOT VERIFIED (cap)** |
| L6 | `JwtUtil.isTokenExpired` is dead code; `LoginRequest` is accepted without `@Valid`; several frontend `setTimeout` timers are not cleared on unmount. | **PASS (cosmetic)** |
| L7 | Duplicate security headers on proxied `/api` and `/auth` responses (nginx `add_header … always` + Spring headers). Cosmetic; the stricter API CSP wins. | **PASS (cosmetic)** |
| L8 | Stray untracked file `springapp/_tmp_os.txt` (1.6 KB snapshot of `OrderService` source). Untidy; will be committed accidentally if `git add -A` is used. | **FAIL (hygiene)** |
| L9 | Concurrency/transaction guarantees are proven on **H2 only**; no run against real MySQL 8.0.40. | **NOT VERIFIED** |
| L10 | No observability beyond `GET /actuator/health` (no metrics/tracing endpoints, no log aggregation, no alerting) and no resource limits/log driver in compose. | **FAIL (operational)** |
| L11 | Two remotes (`origin`, `pub`) point at the same GitHub URL; no tags, releases or `LICENSE`. | **PASS (informational)** |
| L12 | Secret scan of tracked files found no private keys, `AKIA…` keys, `ghp_…` tokens or hardcoded password/secret assignments in main code. `.env.example` holds placeholders only. | **PASS** |

---

## 4. Staging readiness checklist

| # | Item | Status | Evidence |
|---|---|---|---|
| 1 | Base profile cannot touch a populated DB implicitly | **PASS** | `application.properties`: `${DB_URL}/${DB_USERNAME}/${DB_PASSWORD}`, `ddl-auto=validate`, no `jdbc:mysql` fallback; `SafeDatabaseConfigurationTest` |
| 2 | Production profile fails fast on missing/unsafe config | **PASS** | `ProductionConfigurationValidator` (nonblank, `jdbc:mysql:`, 32-byte JWT, explicit HTTPS non-localhost origins) + `ProductionConfigurationValidatorTest` |
| 3 | Dev / docker / test / e2e profile separation | **PASS** | `dev`+`docker` = `update`; `test`+`e2e` = H2 `create` in `src/test/resources` (not packaged); `flyway-test` isolated with safety guard |
| 4 | Schema validation in staging/production | **PASS** | `prod` = `validate`, Flyway disabled; test asserts it |
| 5 | Verified database backup + restore rehearsal | **BLOCKED** | Phase 14 BLOCKED; Phase 14C could not execute SQL (no login path, password-less `root` denied) |
| 6 | Least-privilege DB account for the app | **NOT VERIFIED** | No staging DB exists; no grant evidence captured |
| 7 | Staging host / network / DNS | **BLOCKED** | None exists |
| 8 | Staging secrets management | **BLOCKED** | Only `.env.example` + required `${VAR:?}` interpolation; no secret manager wired |
| 9 | TLS termination + HSTS | **BLOCKED** | nginx HTTP:80 only; no cert config or reverse-proxy doc |
| 10 | Container images build reproducibly | **PASS (prior)** | Phase 13F built both images (684 MB / 74.8 MB); not re-run in this read-only phase |
| 11 | Container health checks | **PASS (prior)** | DB `mysqladmin ping`, backend `/actuator/health`, frontend `/healthz`; all `healthy` in Phase 13F |
| 12 | No published DB/backend ports | **PASS** | `docker-compose.yml`: only frontend `${FRONTEND_PORT:-3000}:80` |
| 13 | nginx proxy + streaming CSV | **PASS (prior)** | `proxy_buffering off`, 300 s read timeout; `Transfer-Encoding: chunked` observed through nginx |
| 14 | CSP for the SPA | **PASS (prior runtime)** | nginx CSP present; browser console empty under it in Phase 13F; not re-checked here (no container started) |
| 15 | Automated test coverage | **PASS** | 229 backend + 66 frontend + 7 E2E, all re-run today |
| 16 | CI enforces tests on the release tree | **FAIL** | Workflow untracked at HEAD; never ran on this tree |
| 17 | Dependency vulnerability gate | **FAIL / NOT VERIFIED** | npm clean; no Maven CVE scan; no audit step in CI |
| 18 | Deployment runbook (rollback, backup, incident) | **FAIL** | Only README env-var guidance; no runbook |
| 19 | Observability / alerting | **FAIL** | Health endpoint only |
| 20 | Reproducible release artefact (tagged commit) | **FAIL** | 49 modified + 129 untracked files; HEAD has 76 files |

---

## 5. Security and integration findings

### 5.1 Verified good (re-checked in this phase)

- **Password handling:** BCrypt via `BCryptPasswordEncoder`; `User.password` is write-only so hashes are never serialised; password policy `^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9])[!-~]{8,}$` on both sides.
- **JWT:** HS256, `issuer = examly-ecom` enforced via `requireIssuer`, expiry enforced, key length ≥ 32 bytes validated at startup by `JwtSecretValidator` (called from `@PostConstruct`).
- **Authorization:** URL rules in `SecurityConfig` plus method gates. Verified in code: `GET /api/products/stats` requires ADMIN at **both** the chain and `@PreAuthorize` (declared before the public product GET); `UserController` is class-level ADMIN; `PaymentController` enforces order ownership for non-admins from the JWT `userId` (never a client parameter); `/api/orders/**` authenticated with ADMIN-only list/export/stats/delete; analytics/RFM/forecast/demand/risk/audit ADMIN-only; `POST /auth/register/admin` ADMIN-only.
- **IDOR:** customer order reads (`/my`, `/my/paged`, `/my/{id}`), cancellation, invoice and payment lookups all derive the user id from the JWT; `cancelOrder` calls `getOrderByIdForUser`.
- **CORS:** exact origins, explicit headers, `*`/`null` filtered out, credentials enabled only against the explicit list, preflight handled by Spring Security before auth.
- **CSP/headers:** API sends `default-src 'none'; frame-ancestors 'none'; base-uri 'none'`, `nosniff`, `DENY`, strict referrer policy, HSTS on HTTPS, restrictive `Permissions-Policy`; the SPA gets the nginx CSP.
- **Rate limiting:** check happens before BCrypt; identical 429 body for known/unknown accounts (no enumeration); `Retry-After`; bounded map with stale sweep and fail-open at capacity; success clears the email counter.
- **Exception handling:** unclassified `RuntimeException`s return a generic 400, `Exception` a generic 500; stack traces stay server-side; known business messages preserved; auth failures logged by exception type only.
- **Injection:** no `nativeQuery` anywhere; all queries are parameterised JPQL; LIKE wildcards escaped; sort fields whitelisted; page size capped at 100.
- **Secrets:** no secret in tracked files, images, logs or the frontend bundle; `VITE_*` is public-only; compose requires `${DB_PASSWORD:?}`, `${MYSQL_ROOT_PASSWORD:?}`, `${JWT_SECRET:?}`.

### 5.2 Integration correctness (checkout / coupons / orders / inventory / admin)

- `OrderService.createOrder` aggregates duplicate product lines into a `TreeMap` (deterministic id order), takes `PESSIMISTIC_WRITE` locks on all requested products (`findAllByIdInForUpdate`), rejects insufficient stock, then deducts; class-level `@Transactional` means a later coupon failure rolls the stock change back.
- Coupon reservation happens under a `PESSIMISTIC_WRITE` coupon lock and the discount is computed server-side; the client cannot set a price or total.
- `cancelOrder` restores stock and coupon usage only for `PENDING`/`PROCESSING`, and rejects a second cancellation.
- `PaymentService.pay` locks the order row (`findByIdForUpdate`), blocks payment of a cancelled order and a second successful payment; amount comes from the stored order.
- Order status transitions are validated against `VALID_STATUSES`; delete restores stock; admin mutations are audit-logged.
- Pagination/filters: validated page/size/sort, status/role whitelists, date/amount range consistency checks, `ALL` sentinel handling, stable secondary sort on id.
- Analytics: `getOrderMetrics`, `getCategorySales`, `getInventory`, `getTimeSeries` are DB aggregates with bounded top-`n`; only `getSummary` (legacy `/api/analytics/summary`) still loads all orders.
- CSV export: `StreamingResponseBody`, 500-row batches, `EXPORT_MAX_ROWS = 100_000`, UTF-8, quoted/escaped fields, filter parity with `/paged`, ADMIN-only.

---

## 6. Test / build evidence

### 6.1 Re-run in this phase (all executed today, read-only, H2 only)

| Check | Command | Result |
|---|---|---|
| Backend unit + integration | `cd springapp && mvn -B -ntp test` | **exit 0 — 229 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** |
| Frontend unit | `cd reactapp && npm run test:run` | **exit 0 — 16 files, 66 tests passed** |
| Frontend lint | `npm run lint` | **exit 0 — 0 errors, 0 warnings** |
| Frontend build | `npm run build` | **exit 0 — built in 7.18 s** |
| E2E (Playwright, H2 `e2e` profile) | `npm run test:e2e` | **exit 0 — 7 passed (38.4 s)** |
| Dependency audit (prod) | `npm audit --omit=dev` | **0 vulnerabilities** |
| Dependency audit (full) | `npm audit` | **0 vulnerabilities** |
| MySQL isolation | grep of the Maven log | 170 mentions of `jdbc:h2:mem`; the single `jdbc:mysql` string is the literal SQL-leak fixture inside `GlobalExceptionHandlerTest` ("Communications link failure to db-internal:3306"), not a connection. E2E log: 0 references to `3306/ecom`. |

Surefire reports confirm the same totals (`tests=229 failures=0 errors=0 skipped=0` across 35 report files). No test was skipped and none was disabled.

### 6.2 Evidence carried from earlier phases (not re-executed here)

- Phase 13F: both images built; compose stack `db`/`backend`/`frontend` all `healthy`; MySQL isolated as `ecom_verify13f`; `nginx -t` OK; SPA render + login under the CSP with an empty console; `/api` and `/auth` proxied; CSV `200 text/csv Transfer-Encoding: chunked`; backend restart recovery; clean shutdown with the volume preserved.
- Phase 16: base profile de-fallback, login limiter rewrite, admin-only product stats, CSP, generic runtime errors, widened password policy, CSV regression tests.

### 6.3 Not run / not verifiable

- **No remote CI run** was observed, triggered or claimed (workflow is untracked at HEAD; no logs available).
- **No Docker command that changes state**; no container was started in this phase, so today's runtime behaviour is inferred from Phase 13F and from static config.
- **No MySQL connection, SQL, Flyway, backup or restore** — Phase 14 adoption is unverified by construction.
- **No Maven CVE scan** (no plugin installed; installing one is out of scope).
- **No load/performance test, no accessibility audit, no browser check in this phase.**

---

## 7. Unresolved risks

### 7.1 Carried forward from Phases 15 and 16 (status re-checked)

| Risk | From | Status now |
|---|---|---|
| Base profile pointed at live `ecom` with `ddl-auto=update` (S5) | 15 | **Resolved** in Phase 16 — no fallback, `validate` |
| Unknown runtime exception text echoed to clients (S9) | 15 | **Resolved** in Phase 16 |
| `GET /api/products/stats` anonymous (S6) | 15 | **Resolved** in Phase 16 (chain + method) |
| No SPA CSP (S7) | 15 | **Resolved**; runtime-confirmed in 13F |
| Narrow password special-character whitelist (S8) | 15 | **Resolved** in Phase 16 |
| JWT in `localStorage`, no revocation (S1/S2) | 15 | **Open** (M1/M2) |
| Per-instance rate limiter (S3) | 15 | **Open but bounded** (M3) |
| Mock payment always succeeds (S4) | 15 | **Open by design** (M4) |
| CSV export untested | 15 | **Resolved** in Phase 16 (12 tests); 100 k cap still untested |
| Docker runtime unverified (13F) | 13, 15 | **Resolved** in Phase 13F (PASS) |
| Database adoption / staging (14) | 14 | **Still BLOCKED** |
| Residual `ecom13f-verify-*` images/volume | 13F | **Present** (L1), harmless, decision pending |

### 7.2 New risks found in this phase

1. **Unreproducible release tree (H1)** — the committed tree would not build the reviewed system: no prod/dev/docker profiles, no compose, no nginx config, no CI, and no `.gitignore` (so a stray `.env` would be committed by default).
2. **CI blindness (H4/M7)** — the workflow that exists covers tests only and has never run on this tree.
3. **No staging target at all (H2)** and **no TLS plan (M9)**.
4. **Operational blindness (L10)** — health endpoint only; no metrics, logs pipeline, or alerts.
5. **No dependency gate (M5)** — a future vulnerable dependency would merge silently.
6. **Stale documentation (M6)** that contradicts the implementation.

---

## 8. Production release blockers

| # | Blocker | Type | Owner action |
|---|---|---|---|
| B1 | Phase 14: verified `mysqldump`, SHA-256, restore rehearsal, least-privilege `ecom_backup` account | Database | Authorize + provide credential access (Phase 14C §9 queries) |
| B2 | No staging environment (host, DB, secrets, TLS, DNS) | Infrastructure | Provision and authorize |
| B3 | Reviewed code is uncommitted (49 M + 129 ??) and HEAD lacks all production configuration | Source control | Commit deliberately (exclude `.env`, `_tmp_os.txt`, build output) and create a release tag |
| B4 | CI has not validated this tree; no dependency-audit or deployment gate | CI/CD | Commit the workflow, add `npm audit`, optionally E2E and image build |
| B5 | No TLS termination / HSTS at the edge | Security/Infra | Front end LB or nginx TLS with certificates |
| B6 | JWT storage + no revocation | Security | Accept formally for demo scope, or implement httpOnly cookies/revocation |
| B7 | Mock payment must not be presented as a real gateway | Product | Label in staging demo; plan real PSP |
| B8 | No observability beyond `/actuator/health` | Operations | Metrics/log shipping + alerts |
| B9 | No rollback/backup/incident runbook | Operations | Write and rehearse |
| B10 | Concurrency and schema validation never exercised against real MySQL 8.0.40 | Database | Run `prod` profile against the staging DB after B1/B2 |

---

## 9. Recommended next phase

**Phase 18 — Release Baseline & Staging Stand-Up (two parts, in this order):**

1. **Baseline the release (safe, no infrastructure needed).**
   Commit the reviewed tree in deliberate, reviewed commits (source, tests, profiles, compose, nginx, CI workflow, `.gitignore`, `.env.example`; exclude `springapp/_tmp_os.txt`, `target/`, `dist/`, `node_modules/`, `.env*` except the example), tag `v0.1.0-staging`, push, and confirm the workflow runs green on that commit. Update `DOCUMENTATION.md` (§15 tests, §17 limitations) so documentation matches behaviour.
2. **Stand up isolated staging (requires authorization).**
   Using the Phase 14C grant recommendation (`SELECT` on `ecom.*` only — no views/triggers/routines/events were found), create `ecom_backup`, take a verified dump with recorded SHA-256, restore it into an isolated MySQL 8.0.40 instance on loopback (e.g. `127.0.0.1:3307`), and run the backend with `SPRING_PROFILES_ACTIVE=prod` against it: confirm `ddl-auto=validate` passes against the real schema, re-run the concurrency-sensitive order/coupon tests against MySQL, then verify the SPA behind TLS-terminating nginx with the CSP active.

Phase 17 should be re-run as a short delta audit after Phase 18 part 1 to confirm H1/H4/M6/M7 are closed.

---

## 10. Overall status and next action

| | |
|---|---|
| **Phase 17 (staging readiness & release re-audit)** | **BLOCKED** — audit complete, but staging readiness cannot be certified: the reviewed code is uncommitted, CI has never validated it, and no staging environment or verified database exists. |
| **Application quality (documented demo scope)** | **PASS** — 229 backend + 66 frontend + 7 E2E tests, clean lint/build, 0 npm vulnerabilities, no Critical/High application defect. |
| **Phase 13F (Docker runtime)** | **PASS** (prior evidence, unchanged) |
| **Phase 14 (database adoption)** | **BLOCKED** |
| **Production release** | **BLOCKED** |

**Next action (single, concrete):** commit and tag the reviewed tree and let CI run on that commit — that is the only step that can be completed without new infrastructure or credentials, and it converts H1/H4/M6/M7 from "unknown" into verifiable facts. Staging stand-up (B1/B2/B10) starts only after the owner authorizes database access and a target environment.

**Compliance confirmation for this phase:** no project file was created other than this report; no SQL was executed and MySQL was never contacted; no account, permission, Flyway, backup or restore operation was performed; no Docker container or volume was created, started or modified; no credential, token, environment value or connection string was printed; no destructive Git command was run and all pre-existing working-tree changes were preserved.
