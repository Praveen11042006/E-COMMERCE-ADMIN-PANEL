# PHASE 13F RESULT — Docker Runtime Verification (RESUMED)

Date: 2026-11-24
**Final status: PASS** — the isolated Compose stack (`ecom13f-verify`) started with all three services healthy, connected only to `ecom_verify13f`, and passed the HTTP, proxy, CSV-streaming, restart, browser/CSP and shutdown checks. Application test suites were rerun and are green.

The previous attempt (2026-11-23) was BLOCKED by a full `C:` drive (~316 KB free) that put the Docker Desktop VM filesystem into read-only mode. That condition is gone: `C:` now has ~11 GB free and the engine performs normal writes.

---

## 1. Disk-space preflight

| Item | Value |
|---|---|
| `C:` volume | 201 GB total, ~190 GB used, **11 GB free (95% used)** |
| `D:` | 151 GB total, **109 GB free** |
| `E:` (project drive) | 125 GB total, **85 GB free** |
| Docker data location | Docker Desktop WSL2 data disk on `C:` (engine root `/var/lib/docker`) |
| Minimum required | 10 GB — **met** (11 GB) |
| Preferred | 15 GB — **not met** |

Proceeded because the mandatory 10 GB floor was satisfied; the shortfall against the 15 GB preference is recorded in §15. No file was deleted automatically to make room.

## 2. Docker engine health

| Item | Result |
|---|---|
| Client | 29.9.3, API 1.56, windows/amd64, context `desktop-linux` |
| Server | Docker Desktop **4.100.1** (247138), engine 29.9.3, API 1.56 |
| containerd / runc | v2.4.2 / 1.5.3 |
| Resources | 8 CPUs, 7.652 GiB, driver overlayfs, root `/var/lib/docker` |
| `docker info` | succeeded (only benign blkio/cgroup-v1 deprecation warnings) |
| Scratch container | `docker run --rm hello-world` → **exit 0** |
| Scratch filesystem write | `docker run --rm alpine sh -c 'echo ok > /tmp/x && cat /tmp/x'` → **ok** |
| Read-only / storage errors | **none** at any point during this run |

The engine was already responsive when this phase began (no manual start was needed). The WSL distro list shows `docker-desktop` in `Stopped` state while the engine served normally, i.e. the daemon runs in Docker Desktop's own VM, not through a user-started distro.

## 3. Previous resource inspection and cleanup

`docker system df`, `docker ps -a`, `docker volume ls`, `docker images` and `docker network ls` were inspected **before** any build:

| Resource from the previous attempt | Current state |
|---|---|
| `ecom13f-verify-db-1`, `ecom13f-verify-backend-1`, `ecom13f-verify-frontend-1` | absent (0 containers) |
| `ecom13f-verify_mysql_data` | absent |
| `ecom13f-verify-backend:latest`, `ecom13f-verify-frontend:latest` | absent |
| Previous build cache (~3 GB) | absent (0 B) |

Docker Desktop had been upgraded (4.84.0 → 4.100.1) and its datastore was **empty** (0 images, 0 containers, 0 volumes, 0 B cache) before this phase, so the stale Phase 13F resources no longer existed. **Nothing was removed in this step, and no unrelated resource was touched.**

Later in this phase the only removals were resources created by this run itself: the three `ecom13f-verify` containers + project network (via `compose ... down`) and the two disposable images pulled for the engine probe (`hello-world`, `alpine`). The four default networks, `mysql:8.0`, both application images and the test volume remain.

## 4. Compose validation

`docker compose -p ecom13f-verify --env-file .env.verify13f config` → **exit 0**.

| Check | Result |
|---|---|
| Project name | `ecom13f-verify` |
| Database URL | `jdbc:mysql://db:3306/ecom_verify13f?...` |
| MySQL host port | **none published** (`3306/tcp`, `33060/tcp` internal only) |
| Backend host port | **none published** (`8080/tcp` internal only) |
| Frontend host port | `0.0.0.0:3300->80/tcp` — **3300** |
| `db` health gate | `mysqladmin ping` (interval 10 s, 12 retries, 30 s start period) |
| Backend gating | `depends_on: db: condition: service_healthy` |
| Frontend gating | `depends_on: backend: condition: service_healthy` |
| Secrets | `${DB_PASSWORD:?}`, `${MYSQL_ROOT_PASSWORD:?}`, `${JWT_SECRET:?}` — all required from the environment |
| Volume | `ecom13f-verify_mysql_data` |
| Profile | `SPRING_PROFILES_ACTIVE: docker` (explicitly not `prod`) |
| Reference to original `ecom` DB | **none** (`DB_NAME=ecom_verify13f` supplied explicitly) |

Note on the database name: the task text mentioned `ecom_verify13f13f` "only if the Compose configuration explicitly requires it". The compose file does not require any such name — it takes `DB_NAME` from the environment — so the documented isolated name **`ecom_verify13f`** was used.

Reviewed without change: `docker-compose.yml`, `springapp/Dockerfile`, `reactapp/Dockerfile`, `reactapp/nginx.conf`, `application-docker.properties`, both `.dockerignore` files, `.env.example`. No Docker-specific defect was found, so **no deployment file was modified**.

## 5. Image build results

`docker compose -p ecom13f-verify build` → **exit 0** (both images built fresh, none reused).

| Image | Size | ID |
|---|---|---|
| `ecom13f-verify-backend:latest` | 684 MB | `59e845888aad` |
| `ecom13f-verify-frontend:latest` | 74.8 MB | `c8e99fabf6bd` |

## 6. Runtime startup, MySQL health and database isolation

`docker compose -p ecom13f-verify up -d` started `db` → `backend` → `frontend` in dependency order with no restart loop and no unexpected exit.

| Evidence | Result |
|---|---|
| `docker compose ps` (final) | db `Up (healthy)`, backend `Up (healthy)`, frontend `Up (healthy)`, `0.0.0.0:3300->80/tcp` |
| Startup ordering | `db` became Healthy before backend started; backend Healthy before frontend started |
| `SHOW DATABASES` | `ecom_verify13f`, `information_schema`, `mysql`, `performance_schema`, `sys` — **no `ecom` database** |
| `information_schema.PROCESSLIST` | every application connection is `ecom_app @ 172.18.0.3 → DB=ecom_verify13f` |
| Filesystem errors | none |

The schema was created by Hibernate (`ddl-auto=update`, docker profile). **Flyway was not executed** (`spring.flyway.enabled=false` in the docker profile; no `flyway` command was ever run).

## 7. Backend health and restart verification

| Check | Result |
|---|---|
| `/actuator/health` (in-container) | **200** |
| `/actuator/health/liveness` | **200** |
| `/actuator/health/readiness` | **200** |
| Healthcheck state | `healthy` (image healthcheck `curl -fsS /actuator/health`) |
| Secret leakage in logs (902 lines) | `DB_PASSWORD` 0 hits, `JWT_SECRET` 0 hits, `MYSQL_ROOT_PASSWORD` 0 hits; no JDBC URL logged |
| Unexpected log errors | one `AuthorizationDeniedException` stack trace, caused by the deliberate unauthenticated 401 probe (expected behaviour, logged at ERROR) |
| `docker compose restart backend` | container restarted; health returned to `healthy` on poll attempt 4 (~20 s) |
| Frontend after backend recovery | `/` 200, `/api/products` 200, `/auth/login` 200 — no manual frontend restart needed |

## 8. Frontend, Nginx and CSP verification

| Check | Result |
|---|---|
| `nginx -t` inside `ecom13f-verify-frontend-1` | `syntax is ok` / `test is successful` |
| `GET /` | **200**, `Content-Type: text/html` |
| `GET /healthz` | **200** |
| Response headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, and the Phase 16 **`Content-Security-Policy`** (`default-src 'self'; … script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'`) |
| Browser load (`http://localhost:3300/`) | SPA renders at `/login`; bundle `assets/index-*.js` loads 200; no blank page |
| Browser console | **empty** — no CSP violations, no JS errors |
| Browser login (test account) | `POST /auth/login` 200 → SPA navigates to `/admin/products` |
| Admin page render | Product Inventory with live KPI values (Total 1 / In Stock 1 / Low Stock 1), table showing the smoke-test product, pagination footer |
| Chunked assets | `AdminDashboard-*.js`, `Profile-*.js` loaded 200 (CSP allows the Vite bundle) |
| Unauthenticated API probes | `GET /api/orders` **401**, `GET /api/products/stats` **401**, `GET /api/users` **401** |

The single failed network entry was `https://example.com/widget.png` (`ERR_BLOCKED_BY_ORB`) — the deliberately fake image URL in the smoke-test product, not a CSP or proxy problem.

## 9. `/api` and `/auth` proxy verification

Observed in the browser on `http://localhost:3300` (no cross-origin host involved):

| Request | Result |
|---|---|
| `GET http://localhost:3300/api/products` | 200 (Fetch) |
| `GET http://localhost:3300/api/products/paged?page=0&size=20&sort=name,asc` | 200 (XHR) |
| `GET http://localhost:3300/api/products/stats` | 200 (XHR, admin token) |
| `POST http://localhost:3300/auth/login` | 200 (XHR) |

Additional via `curl` through port 3300: `/auth/register` 201, `/auth/login` 200, `/auth/me` 200, `/api/products/paged` 200 with content, `/api/orders?page=0&size=5` 200, `/api/orders/paged` 200.

## 10. CSV streaming verification

`GET /api/orders/export` through nginx (admin token):

```
HTTP/1.1 200
Content-Type: text/csv
Transfer-Encoding: chunked
Content-Disposition: attachment; filename="orders-export-2026-11-24.csv"
```

Body (header + real exported row, produced from the smoke-test order):

```
Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count
1,"Verify User","verify13f@example.test",,"PENDING",19.99,"2026-11-24T06:36:40.863271","1 Test Street, Verify City",1
```

`Transfer-Encoding: chunked` plus the matching `nginx.conf` `proxy_buffering off` confirms the response is streamed through the proxy rather than buffered.

## 11. Shutdown result

`docker compose -p ecom13f-verify --env-file .env.verify13f down` removed the three containers and the `ecom13f-verify_default` network cleanly. **The test volume `ecom13f-verify_mysql_data` (197.9 MB) was preserved** (no `-v`), as required; it now holds only synthetic verification data (1 test user, 1 test product, 1 test order).

The temporary `.env.verify13f` was deleted after shutdown; only the pre-existing `.env.example` placeholder file remains, and no other environment file was touched. Retained afterwards: 3 images (`ecom13f-verify-backend` 684 MB, `ecom13f-verify-frontend` 74.8 MB, `mysql:8.0` 1.1 GB), the test volume and 2.18 GB of build cache. Removing them later is optional and safely scoped:
`docker volume rm ecom13f-verify_mysql_data`, `docker rmi ecom13f-verify-backend ecom13f-verify-frontend`, `docker builder prune`.

## 12. Application test suites (rerun after Docker work)

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` | **229 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** |
| `cd reactapp && npm run test:run` | **66 tests passed (16 files)** |
| `cd reactapp && npm run lint` | **0 errors, 0 warnings** (exit 0) |
| `cd reactapp && npm run build` | **exit 0** (built in 12.60 s) |
| `cd reactapp && npm run test:e2e` | **7 passed** (37.8 s) |

These suites are reported separately from the Docker evidence above; Docker runtime success rests on the live container/endpoint checks in §6–§11, not on these tests.

## 13. Files changed

| File | Reason |
|---|---|
| `PHASE_13F_RESULT.md` | This report (rewritten from BLOCKED to PASS). |

**No application source, Dockerfile, compose, nginx, profile or dependency file was modified** in this phase. `reactapp/package.json`, `reactapp/package-lock.json` and the other listed `git status` entries are pre-existing modifications from earlier phases, untouched here. No commit, reset, checkout, restore, stash or clean was performed.

## 14. Remaining limitations and observations

1. **Disk headroom is thin** — 11 GB free on `C:` (10 GB floor met, 15 GB preference not met). The run added 1.87 GB of images, 198 MB of volume and 2.18 GB of build cache to a 95%-full system drive.
2. **`docker` profile, not `prod`** — the demo stack runs Hibernate `update` on the fresh volume; `prod` schema validation and HTTPS enforcement were not exercised here (that belongs to staging/release readiness, which remains out of scope).
3. **Duplicate security headers on proxied responses** — because `add_header … always` in `nginx.conf` also applies to proxied `/api` and `/auth` replies, those responses carry both the backend headers and the nginx ones (e.g. `X-Content-Type-Options` and `Content-Security-Policy` appear twice, and the stricter backend CSP wins on API responses). This is cosmetic for JSON/CSV payloads and was not treated as a defect; the SPA document itself receives only the nginx values.
4. **Role-negative runtime check not exercised** — an authenticated non-admin token was not replayed against `/api/orders/export` in this run (the account was promoted to ADMIN for the export test). The 403 path is covered by the backend test suite, not by a live Docker request.
5. **Single-instance login rate limiting** remains in place (documented Phase 16 limitation) — not changed by this phase.
6. **No production claim** — this verifies a local demo stack only. Phase 14 remains BLOCKED and Phase 13F does not change that status.

## 15. Explicit confirmations

- **Original `ecom` database: untouched.** No connection to it was made; `SHOW DATABASES` on the isolated server never contained it, and no host MySQL client was used. All runtime traffic went to `ecom_verify13f` through the private Compose network.
- **Flyway: never run.** No `migrate`, `baseline`, `repair` or `clean`; the docker profile has `spring.flyway.enabled=false` and the schema came from Hibernate `update`.
- **Dependencies: unchanged.** No install/upgrade; the frontend image ran `npm ci` from the committed lockfile inside the build stage and the backend built from the existing `pom.xml`.
- **Unrelated Docker resources: untouched.** No `docker system prune`, no `volume prune`, no removal of the default networks, `mysql:8.0`, or anything not created by this phase.
- **Secrets: not printed or committed.** Credentials were generated with `openssl rand`, written to a git-ignored file (`.env.verify13f`, confirmed via `git check-ignore`), used only through environment variables, verified absent from container logs, and the file was deleted after shutdown.
- **Git: no destructive operation** was performed, and all pre-existing working-tree changes were preserved.
