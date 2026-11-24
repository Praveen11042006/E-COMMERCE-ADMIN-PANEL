# PHASE 16 — APPLICATION HARDENING & REGRESSION COVERAGE

**Date:** 2026-11-24
**Project:** `E:\Subjects\ecomAlmostdone\ecomAlmostdone`
**Scope:** application-level hardening (safe DB configuration, login rate limiter, product-stats authorization, nginx CSP, generic runtime errors, password policy) plus CSV export regression coverage.

---

## 1. Overall status

**PASS** — all applicable tasks (A–G) were completed and every verification check that can run on this host succeeded.

This is **not** a production-release statement. Overall release readiness remains **BLOCKED** (Phase 14 = BLOCKED; Phase 13F = PAUSED, untouched in this phase).

---

## 2. Files inspected and changed

### Inspected (read-only)
- `springapp/src/main/java/.../config/SecurityConfig.java`, `CorsConfig.java`, `ProductionConfigurationValidator.java`, `FlywayTestSafetyGuard.java`
- `springapp/src/main/java/.../controller/{Authentication,Product,Order,User}Controller.java`
- `springapp/src/main/java/.../service/{Order,Product,User}Service.java`
- `springapp/src/main/java/.../repository/OrderRepository.java`
- `springapp/src/main/java/.../exception/GlobalExceptionHandler.java`
- `springapp/src/main/java/.../dto/PageRequestParams.java`
- `springapp/src/main/java/.../model/Order.java`
- All `application*.properties` (main, prod, docker, flyway-test, test, e2e) and `docker-compose.yml`, `.env.example`, `README.md`, `DOCUMENTATION.md`
- `reactapp/nginx.conf`, `reactapp/Dockerfile`, `reactapp/index.html`, `reactapp/src/{App.jsx,auth/Register.jsx,utils/api.js}`, `reactapp/src/components/ProductList.jsx`
- Existing tests: `LoginRateLimitTest`, `PaginationApiTest`, `ProductPaginationTest`, `ProductControllerTest`, `OrderAnalyticsOptimizationTest`, `SecurityHeadersAndCorsTest`, `FlywayTestSafetyGuardTest`, `ProductionConfigurationValidatorTest`, `Register.test.jsx`

### Changed
| File | Change |
| --- | --- |
| `springapp/src/main/resources/application.properties` | Removed the implicit `jdbc:mysql://localhost:3306/ecom` fallback; DB credentials now resolve strictly from `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`. `ddl-auto` changed `update` → `validate`. Added `security.login.max-tracked-keys`. |
| `springapp/src/main/resources/application-dev.properties` | **New** explicit local-development profile (`ddl-auto=update`), opt-in only. |
| `springapp/src/main/java/.../utils/LoginRateLimiter.java` | Rewritten: one bounded, synchronized store instead of three unbounded maps; stale-entry sweep; fail-open at capacity; injectable clock; documented single-instance limitation. |
| `springapp/src/main/java/.../controller/ProductController.java` | `GET /api/products/stats` now `@PreAuthorize("hasRole('ADMIN')")`. |
| `springapp/src/main/java/.../config/SecurityConfig.java` | Chain-level rule: `GET /api/products/stats` requires ADMIN (declared before the public product GET rule). |
| `springapp/src/main/java/.../exception/GlobalExceptionHandler.java` | Unexpected `RuntimeException`s no longer echo their message; generic "Request could not be processed"; stack trace logged server-side; known business messages preserved. |
| `springapp/src/main/java/.../controller/AuthenticationController.java` | Password pattern accepts any printable ASCII special character (including `#`, `^`) instead of the `@$!%?&` whitelist. |
| `reactapp/src/auth/Register.jsx` | Same password rule as the backend. |
| `reactapp/nginx.conf` | Restrictive `Content-Security-Policy` header for the SPA shell. |
| `README.md` | Documents that there is no implicit database and how to use the explicit `dev` profile. |
| `springapp/src/test/java/.../controller/ProductPaginationTest.java` | Stats assertions now send an ADMIN token. |

### Added (tests + config)
- `springapp/src/test/java/.../utils/LoginRateLimiterTest.java` (10 tests)
- `springapp/src/test/java/.../controller/OrderCsvExportTest.java` (12 tests)
- `springapp/src/test/java/.../controller/ProductStatsAuthorizationTest.java` (4 tests)
- `springapp/src/test/java/.../controller/PasswordPolicyTest.java` (4 tests)
- `springapp/src/test/java/.../exception/GlobalExceptionHandlerTest.java` (7 tests)
- `springapp/src/test/java/.../config/SafeDatabaseConfigurationTest.java` (4 tests)
- `reactapp/src/auth/Register.test.jsx` (+3 tests: `#` and `^` accepted, missing special character rejected)

No dependency, lockfile, schema, migration, `pom.xml` or `package.json` change was made in this phase.

---

## 3. Task results

### A. Safe database configuration — DONE
- Base profile now declares `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}` with **no fallback**, so a bare `spring-boot:run` fails fast at placeholder resolution instead of silently connecting to the populated `ecom` database.
- Base `ddl-auto=validate`; `application-prod.properties` still `validate` with Flyway disabled.
- `application-docker.properties` unchanged (`ddl-auto=update`, no datasource of its own — compose supplies `DB_URL`), so the Docker demo still works when credentials are provided.
- H2 test/e2e profiles unchanged (`ddl-auto=create`, in-memory H2), `flyway-test` guard unchanged.
- New explicit `dev` profile + README instructions (disposable database only).
- Guarded by `SafeDatabaseConfigurationTest` (asserts no `jdbc:mysql` fallback in the base file, validate-only base/prod, update only in `dev`/`docker`, H2 in test/e2e).
- No MySQL connection was made at any point.

### B. LoginRateLimiter hardening — DONE
- One `HashMap` of small `Entry` objects, all access inside `synchronized` methods (thread-safe by construction; login cost is dominated by BCrypt).
- Bounded to `security.login.max-tracked-keys` (default 10 000). Stale entries (idle > window + lockout) are swept every 256 failures and whenever the map is full.
- When the map is still full of live keys, an unknown key is **not** tracked (fail open) rather than evicting an existing counter, so a flood of random emails cannot push out the per-IP counter that bounds the same flood.
- Limits preserved exactly: 5 failures/email/15 min, 20/IP/15 min, HTTP 429 + `Retry-After`, identical blocked body (no account enumeration), successful login clears the email counter.
- Tests: threshold, 429 + `Retry-After` value, lockout expiry, window-expiry counter reset, success reset, IP threshold, capacity bound (100 keys → 3), no eviction at capacity, stale sweep makes room, concurrent same-key counting (8×40 → blocked), concurrent distinct keys stay within capacity with no errors (10 tests, mock clock — no sleeps).
- **Documented limitation:** counters are per application instance; horizontally scaled deployments multiply the effective threshold and need a shared store.

### C. Product statistics authorization — DONE
- `GET /api/products/stats` requires ADMIN at both the URL chain and the method (`@PreAuthorize`).
- Tests: anonymous → 401, customer → 403, administrator → 200 with correct totals, and catalogue endpoints (`/api/products`, `/api/products/paged`) remain public.
- Frontend check: `fetchProductStats` is only used by the admin-only `ProductList` route (`/admin/products`), so no UI breaks.

### D. Content Security Policy — DONE (not browser-verified)
`reactapp/nginx.conf` now sends:

```
default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none';
script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:;
font-src 'self' data:; connect-src 'self'; form-action 'self'
```

- Compatibility checked against the actual bundle: `index.html` loads one external module script (no inline scripts, no eval) → `script-src 'self'`; React writes inline `style` attributes (analytics heatmap, product cards) → `'unsafe-inline'` **only** in `style-src`; admin-entered product `imageUrl` may live on any HTTPS origin → `img-src ... https:` (not `*`); the Docker build sets an empty `VITE_API_BASE_URL`, so all API calls are same-origin through the nginx proxy → `connect-src 'self'`.
- Limitation: `nginx -t` and a real browser render under this policy could not be run (no nginx binary on the host, Docker is paused per the Phase 13F pause). The policy is therefore statically reviewed, not runtime-verified.

### E. Generic runtime error responses — DONE
- `GlobalExceptionHandler.handleRuntime` no longer returns `ex.getMessage()`. Unclassified runtime exceptions are logged with their stack trace (`log.warn`) and answered with `400 {"message":"Request could not be processed"}` — the same status the handler returned before, so no contract change.
- Preserved: `Order not found` → 404, `Product not found` → 400, `User not found with id N` → 400 with the same text; `ValidationException`, bean validation, auth, access-denied and the generic 500 handler are untouched.
- Tests: 7 (`GlobalExceptionHandlerTest`) covering generic replacement, SQL fragment suppression, null message, all three business messages, and the 500 fallback.

### F. Password validation — DONE
- Backend and frontend now use the same rule: `^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9])[!-~]{8,}$` — at least 8 printable ASCII characters with lowercase, uppercase, a digit and any non-alphanumeric character; whitespace/control/non-ASCII rejected. Minimum length and complexity are unchanged; only the special-character whitelist was widened.
- Backend tests (4): `#` and `^` accepted; other printables (`~ { ``) accepted; missing uppercase/lowercase/digit/special, 7 chars, and embedded space rejected; accepted password stored only as a BCrypt hash.
- Frontend tests (3): `Str0ng#Pass` and `Str0ng^Pass` accepted, `Password123` rejected before the API call.

### G. CSV export regression tests — DONE
`OrderCsvExportTest` (12 tests, MockMvc + H2) covers:
- Authorization: anonymous 401, customer 403, admin 200.
- Headers: `text/csv`, `Content-Disposition: attachment; … orders-export-<date>.csv`.
- Exact header row and column order (`Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count`).
- Escaping: comma-quoted name, doubled quotes in the email, newline inside the shipping address.
- One CSV row per order, in the requested id order.
- Filter parity with `GET /api/orders/paged` for status, free-text search, and combined date + amount ranges; invalid filters → 400.
- Empty export → header only.
- Streaming across the 500-row batch boundary: 501 orders → 502 lines through `StreamingResponseBody`.
- Batch size 500 is additionally covered at service level by the existing `OrderAnalyticsOptimizationTest` (501 rows → two pages).
- Not exercised: the 100 000-row maximum (would require materialising 100k rows for no contract gain; the cap is code-reviewed only). No defect was found, so the endpoint contract is unchanged.

---

## 4. Verification results (all actually executed)

| Check | Result |
| --- | --- |
| `mvn -B -ntp test` (backend) | **229 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS (baseline 188 → +41) |
| `npm run test:run` (frontend) | **66 tests / 16 files passed** (baseline 63 → +3) |
| `npm run lint` | 0 errors, 0 warnings |
| `npm run build` | exit 0 (`✓ built in 6.26s`) |
| `npm run test:e2e` (Playwright) | **7 passed** (38.6s), against the H2-backed `e2e` profile |
| `npm audit --omit=dev` | 0 vulnerabilities |
| `npm audit` (full) | 0 vulnerabilities |
| Dependency/lockfile changes | none made in this phase (`package.json`/`package-lock.json` remain in their pre-existing, pre-Phase-16 modified state) |
| Secret scan of touched files | no credentials added; only pre-existing test fixtures (`test-password`, dummy JWT secrets in test code) |
| H2 isolation | test classpath uses `jdbc:h2:mem:testdb` (`ddl-auto=create`); e2e uses `jdbc:h2:mem:e2e;MODE=MySQL`; asserted by `SafeDatabaseConfigurationTest`; no test report mentions `jdbc:mysql:` |

No test failed and no test was skipped. One real failure was hit and fixed during the phase: the rewritten `LoginRateLimiter` initially had two constructors, so Spring reported "No default constructor found" and 174 tests errored at context load; annotating the public constructor with `@Autowired` fixed the root cause and the full suite was rerun (results above).

---

## 5. npm audit

- Production dependencies: `found 0 vulnerabilities`.
- Full tree (incl. dev): `found 0 vulnerabilities`.

---

## 6. Security improvements and remaining risks

### Improvements
1. A bare start can no longer target (and mutate) the populated `ecom` database; the schema-mutating behaviour is confined to the explicit `dev` and `docker` profiles.
2. The login limiter is memory-bounded and stale-safe, with its single-instance nature documented instead of implicit.
3. Whole-table inventory statistics (counts and valuation) are no longer anonymously readable.
4. Unexpected runtime exception text (SQL, hosts, framework detail) no longer reaches clients; it stays in server logs.
5. Passwords with legitimate special characters (`#`, `^`, …) are no longer rejected; frontend and backend rules are identical.
6. The SPA shell is served with a restrictive CSP instead of no CSP.
7. The CSV export contract is now regression-tested end to end.

### Remaining risks (unchanged from Phase 15 unless noted)
- **Medium** — JWT remains in `localStorage` with no revocation list and no httpOnly-cookie alternative.
- **Medium** — the mock payment provider still reports success for every valid request.
- **Medium** — rate limiting is per instance (now explicit and documented); a shared store is needed before scaling out.
- **Low** — CSP is statically reviewed only; it must be confirmed in a browser once Docker/nginx can run again.
- **Low** — the 100 000-row export cap has no test (documented above).

---

## 7. Failed / skipped tests

None failed, none skipped. Limitations that are **not** test results:
- `nginx -t` and a browser render of the SPA under the new CSP were not performed (no nginx on the host; Docker is paused by instruction).
- The 100 000-row CSV cap was not executed.
- The base profile's fail-fast startup without `DB_URL` was reasoned from configuration and asserted by a config test, not by launching the application (launching is unnecessary and would risk touching MySQL).
- No remote CI run was performed; GitHub Actions was not triggered or observed.

---

## 8. Confirmation of scope discipline

- **No** connection to the MySQL `ecom` database and **no** SQL executed.
- **No** Flyway command (`migrate`, `baseline`, `repair`, `clean`) was run; Flyway remains disabled everywhere except the isolated `flyway-test` guard.
- **No** Docker command was run; Phase 13F stays PAUSED.
- **No** schema, migration or data change; **no** dependency install/upgrade; **no** lockfile edit.
- **No** production deployment or publish.
- **No** destructive Git command; all pre-existing uncommitted/untracked work preserved.
- No secret, password, token or connection string was printed, logged or committed.

---

## 9. Remaining blockers and recommended next phase

**Phase 16: PASS (application-level). Overall production release: still BLOCKED.**

Remaining blockers are outside this phase:
1. **Phase 14 (BLOCKED):** no verified data backup/restore rehearsal, no isolated staging instance or credentials, Flyway adoption still unexecuted.
2. **Phase 13F (PAUSED):** container runtime never reached a healthy state (host disk exhaustion / read-only Docker VM); residual `ecom13f-verify-*` resources still need an explicit decision on removal.

Recommended next phase: **Phase 17 — staging readiness & release re-audit**: with explicit authorization and credentials, take a verified `mysqldump` of `ecom`, rehearse a restore into an isolated schema, stand up isolated staging, run the app with `SPRING_PROFILES_ACTIVE=prod` against it, confirm the CSP in a browser behind nginx, and only then revisit Phase 13F runtime evidence before any release decision.
