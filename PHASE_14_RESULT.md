# PHASE 14 RESULT — Production Database Adoption & Staging Validation

Date: 2026-11-23
**Overall status: BLOCKED**

Completed and verified: repository/configuration audit, static schema equivalence of the existing dump vs Flyway V1, Flyway-adoption design review, and all application test suites. Not completed: verified backup, restore rehearsal, isolated staging validation, live schema comparison, and data-compatibility proof — because no database credentials for a verified target are available, no complete (data-bearing) backup exists, and no staging instance or approved staging account exists.

No database was contacted during this phase. No DDL/DML, Flyway operation, baseline, migration, account creation, or backup/restore was executed.

---

## 1. Scope completed

| Task | Result |
|---|---|
| A. Inspect adoption strategy, dump, V1, Flyway/profiles, Phase 11/13/13E evidence | **Completed** |
| B. Backup & restore readiness | **Audited — FAIL (no valid backup exists)**; procedure documented, not executed |
| C. Isolated staging validation | **BLOCKED** — no staging instance/credentials; creating one requires authorization |
| D. Schema compatibility (7 tables) | **Static comparison PASS** (dump vs V1); live-database comparison **not possible** |
| E. Flyway adoption strategy | **Reviewed**, recommended path documented, nothing executed |
| F. Application compatibility checks | **Completed — all pass** |
| G. Documentation | This report |

## 2. Files inspected / changed

**Inspected (read-only):**
`Dump20261031.sql`; `springapp/src/main/resources/db/migration/V1__baseline_schema.sql`; `application.properties`, `application-prod.properties`, `application-docker.properties`, `application-flyway-test.properties`; `springapp/src/test/resources/application.properties`, `application-e2e.properties`; `FlywayTestSafetyGuard.java`, `ProductionConfigurationValidator.java`, `spring.factories`; `springapp/pom.xml`; `reactapp/playwright.config.js`, `reactapp/e2e/start-backend.mjs`, `reactapp/e2e/E2ESeedData.java`; `.github/workflows/quality.yml`; `README.md`, `DOCUMENTATION.md`; `PHASE_11A_RESULT.md`, `PHASE_11D_RESULT.md`, `PHASE_13_RESULT.md`, `PHASE_13E_RESULT.md`, `PHASE_13F_RESULT.md`, `PROJECT_REMAINING_PHASES_AUDIT.md`; `docker-compose.yml`.

**Changed:** `PHASE_14_RESULT.md` only. No application, configuration, schema, or test file was modified. All 166 uncommitted/untracked pre-existing changes were left untouched.

## 3. Database identity, isolation and safety evidence

- No database connection was opened this phase. The MySQL 8.0 service (`MySQL80`) listens on `0.0.0.0:3306`, and the JDBC client/`mysqldump` binaries are installed, but **no credentials for `ecom` or `ecom_flyway_test` are available in the project or environment**:
  - `FLYWAY_TEST_DB_URL`, `FLYWAY_TEST_DB_USERNAME`, `FLYWAY_TEST_DB_PASSWORD`: **unset**
  - `DB_URL`, `DB_USERNAME`: **unset** (so app defaults would resolve to `jdbc:mysql://localhost:3306/ecom` — deliberately not used)
  - No `.env` file in the project (only `.env.example`); no `~/.mylogin.cnf`; no backup/restore scripts in the repository.
- Because target identity and isolation cannot be proven without credentials, the safety rule "stop immediately if database identity or isolation cannot be proven" applies. **No read-only query was attempted**, including the Phase 13E incident check.
- Configuration isolation verified statically:
  - Base: `spring.flyway.enabled=false`; `ddl-auto=update` (local dev fallback only, default URL points at `ecom`).
  - **prod:** `ddl-auto=validate`, `spring.flyway.enabled=false`, `show-details=never`; all DB/JWT/CORS values mandatory (`${VAR}` with no fallback) and validated by `ProductionConfigurationValidator`.
  - **tests:** in-memory H2, `ddl-auto=create`, Flyway disabled (`src/test/resources/application.properties`).
  - **e2e:** `jdbc:h2:mem:e2e;MODE=MySQL`, `ddl-auto=create`, Flyway disabled, file lives in test resources only; `e2e/start-backend.mjs` builds an explicit test classpath, so the Phase 13E fallback path is closed.
  - **flyway-test:** only profile enabling Flyway; guarded by `FlywayTestSafetyGuard` (EnvironmentPostProcessor) which rejects `prod`+`flyway-test`, disabled Flyway, non-MySQL URL, any target other than `127.0.0.1:3306/ecom_flyway_test`, or any username other than `flyway_test`.
- No secret values were printed, logged, or written. (A `DB_PASSWORD` variable happens to be set in the shell environment; its origin/target is unknown, so it was **not** used.)

## 4. Backup and restore results

**A valid backup does not exist.**

- `Dump20261031.sql` (6,516 bytes, dump header `Host: localhost Database: ecom`, MySQL 8.0.40) contains **schema only**: 7 `CREATE TABLE` statements and **0 `INSERT INTO` statements** — no rows of data.
- It contains **no** triggers, routines, events, `DEFINER`, `DELIMITER`, or `CREATE DATABASE` statements, and no `USE` statement.
- It includes `DROP TABLE IF EXISTS` per table (destructive on restore) and is **not** integrity-verified (no checksum, no restore rehearsal, no row-count comparison).
- It is a single dated artifact; there is no backup schedule, retention policy, or automation in the repo or CI workflow.

Therefore data, and any routines/triggers that may exist in the live server, are **not covered**. Whether the live `ecom` schema still equals this 2026-10-31 dump cannot be established without credentials (relevant because of the Phase 13E incident, §7).

### Required backup + restore rehearsal procedure (not executed — needs DBA authorization)

Use a dedicated read-only backup account, never root, and never target a database other than `ecom`:

1. Full consistent dump (schema + data + routines/triggers/events):
   `mysqldump --single-transaction --quick --routines --triggers --events --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 --databases ecom > ecom_full_<timestamp>.sql`
2. Immediately checksum the artifact and store it off-host (SHA-256), record size and row counts reported by the dump.
3. Restore rehearsal into a **new, isolated** schema (e.g. `ecom_restore_rehearsal`) on a non-production instance:
   `mysql -u <rehearsal_user> -p < ecom_full_<timestamp>.sql` (with the file's `--databases` target overridden/edited to the rehearsal schema, or dumped per-schema and imported with `mysql <schema>`).
4. Verify: 7 tables present; per-table `COUNT(*)` equals source counts; `CHECKSUM TABLE` on every table (source vs restore); FK count = 2 (`order_item`); unique keys = 2; `SHOW CREATE TABLE` equality for all 7; `information_schema` engine/collation comparison.
5. Application acceptance: start the backend against the rehearsal schema with `SPRING_PROFILES_ACTIVE=prod` (so `ddl-auto=validate`) and confirm startup succeeds, then run read-only smoke tests.

## 5. Staging isolation evidence

**No isolated staging environment is available and none was created.**

- No staging host/instance is configured anywhere in the repo; there is no `application-staging.properties`, no staging compose override, and no staging secrets.
- `ecom_flyway_test` (the Phase 11D test database) exists per documentation, but its current state is unverified and no `FLYWAY_TEST_DB_*` credentials are present. It is a Flyway test target, not a staging replica of production data.
- Creating a staging database/account is explicitly gated by this phase ("Do not create databases or accounts without explicit authorization"), so none was created.
- Docker Desktop is running again and the `mysql:8.0` image is present locally, so a disposable container could host staging **without touching host MySQL**, but:
  - Host `C:` has only ~479 MB free (the Phase 13F run exhausted it); starting MySQL there risks repeating the read-only-filesystem failure.
  - Creating that container/database/account requires your approval.

**Exact setup required (for approval):**
1. Free ≥5 GB on `C:` (or move the Docker disk image to `E:`), confirm `docker info` and a scratch container run.
2. `docker run -d --name ecom14-staging-mysql -e MYSQL_ROOT_PASSWORD=<generated> -e MYSQL_DATABASE=ecom_staging -e MYSQL_USER=staging_app -e MYSQL_PASSWORD=<generated> -p 127.0.0.1:3310:3306 mysql:8.0` (loopback-only publish; dedicated least-privilege account, never production credentials).
3. Restore the verified full dump into `ecom_staging`, then prove isolation: `SELECT @@hostname, DATABASE()`, current user, and schema name `ecom_staging` — no operation may name `ecom`.
4. Use only `127.0.0.1:3310` for staging work; keep the original `localhost:3306/ecom` untouched.

## 6. Schema comparison results

Method: normalized comparison of the dump's `CREATE TABLE` blocks against `V1__baseline_schema.sql` (dump directives, comments, `SET`, `DROP TABLE`, and whitespace removed; tables compared member-by-member).

| Check | Result |
|---|---|
| Table set | Identical: `audit_logs`, `coupons`, `order_item`, `orders`, `payments`, `product`, `users` (7/7) |
| Per-table DDL (columns, types, lengths, nullability, AUTO_INCREMENT, PKs, unique keys, FKs, index names, defaults, engine, collation) | **Identical for all 7** (`normalized_identical: true`) |
| `order_item` FKs | 2 (`order_id`→`orders.id`, `product_id`→`product.id`), matching |
| Unique keys | `coupons.code`, `users.email`, matching |
| Engine/collation | InnoDB / utf8mb4 / `utf8mb4_0900_ai_ci` on every table, matching |
| Data statements in dump | 0 |
| Triggers/routines/events in dump | 0 |
| Table creation order | V1 creates `order_item` last (FK-safe); dump lists it third, irrelevant for an ordered restore |

This proves the V1 migration is a faithful rendition of the **dump**. It does **not** prove equivalence with the **live** `ecom` database: the dump is schema-only, dated 2026-10-31, and unverified against the current server state. Matching structure alone would still not prove schema equivalence (row types, live defaults, orphan rows, triggers are unproven).

## 7. Phase 13E incident assessment

- Documented in `PHASE_13E_RESULT.md` §7: the first E2E backend launch used `spring-boot:run -Dspring-boot.run.useTestClasspath=true`, which did not place test resources on the runtime classpath. The app started on the normal configuration against `jdbc:mysql://localhost:3306/ecom` with `ddl-auto=update` for ~2 minutes. The E2E seed component was not loaded, so the suite inserted no data; Hibernate's update check may have run.
- **Current state cannot be verified** without credentials, so it is unknown whether Hibernate added/dropped anything in `ecom` (or its `flyway_schema_history`-free schema). Given the entities matched the dump in Phase 11D, a no-op is likely but unproven.
- The launcher was replaced by `e2e/start-backend.mjs`, which builds an explicit test classpath. Verified now: `application-e2e.properties` exists under `src/test/resources` with H2 only, and the script activates `-Dspring.profiles.active=e2e` — the accidental MySQL fallback path is closed for the E2E suite.
- Residual risk: the base profile still defaults to `jdbc:mysql://localhost:3306/ecom` with `ddl-auto=update`. Any developer running `spring-boot:run` without env vars targets the live database. Recommend (future, needs approval) either a local-only profile or `ddl-auto=validate` as the base default.

## 8. Flyway adoption assessment

Current settings confirmed: `baseline-on-migrate=false`; Flyway disabled in base, prod, H2-test, e2e, and docker profiles; enabled only in `flyway-test`; prod uses `ddl-auto=validate`.

**Why the current configuration cannot be adopted as-is:** on a populated database with no `flyway_schema_history`, Flyway fails with "Found non-empty schema ... without schema history table" because `baseline-on-migrate=false`. Adoption therefore requires an explicit **baseline** operation — a modifying operation that needs separate approval and a verified backup first.

**Recommended sequence (not executed):**
1. Complete and verify the backup and restore rehearsal (§4).
2. Build staging from that backup; confirm isolation (§5).
3. Create a dedicated least-privilege migration account granted only on the staging schema (no root, no `ecom` privileges).
4. Run `flyway baseline -baselineVersion=1 -baselineDescription="existing schema pre-Flyway"` against **staging only**, then `flyway info`/`validate` to confirm baseline = V1 and no pending migrations.
5. Start the app on staging with `SPRING_PROFILES_ACTIVE=prod` (`ddl-auto=validate`) and run read-only smoke tests.
6. Only after staging sign-off, repeat steps 4–5 on production inside an approved change window, with backup and rollback ready. Do **not** flip `baseline-on-migrate=true` in production as a shortcut; keep an explicit, logged baseline.
7. `FlywayTestSafetyGuard` currently pins flyway-test to `127.0.0.1:3306/ecom_flyway_test` + user `flyway_test`; staging on a different port/host needs a reviewed guard extension (code change) or a dedicated staging profile. Flagged, not changed.

## 9. Application test results (actual, this run)

| Command | Result |
|---|---|
| `cd springapp && mvn -B -ntp test` | **188 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** |
| `cd reactapp && npm run test:run` | **16 files, 62 tests passed** |
| `cd reactapp && npm run lint` | **0 errors, 0 warnings** |
| `cd reactapp && npm run build` | **exit 0**, built in 9.86 s |

None of these touched MySQL: backend tests use in-memory H2 (`ddl-auto=create`), no Testcontainers dependency exists, and the CI workflow (`.github/workflows/quality.yml`) runs only `npm ci/test/lint/build` and `./mvnw test`. Flyway guard tests use synthetic environments only.

## 10. Database safety confirmation and limitations

- No connection to `ecom` was made; no DDL/DML, no Flyway `migrate/baseline/repair/clean`, no schema update, no account or database creation, no backup created/deleted.
- Production configuration unchanged: Flyway stays disabled, `ddl-auto=validate`.
- All pre-existing files and 166 uncommitted changes preserved; no Git state was altered.
- Limitations: no live schema introspection, no row counts, no `CHECKSUM TABLE`, no data-compatibility checks, no restore rehearsal, no staging run, and no current evidence about the Phase 13E incident's effect on `ecom`.

## 11. Outstanding blockers

1. **No verified backup** — existing dump is schema-only and unvalidated (§4).
2. **No restore rehearsal** — never performed or recorded.
3. **No isolated staging database** — requires approval and ~5 GB free disk (§5).
4. **No database credentials** — cannot prove target identity or inspect current `ecom` state (including Phase 13E impact).
5. **Flyway adoption unapproved** — baseline on any populated database is a modifying operation.

## 12. Exact next recommended action

Obtain, from the database owner, (a) approval to create an isolated staging MySQL instance and a least-privilege account, and (b) credentials/authorization to take a full `mysqldump` of `ecom` with routines/triggers. Then execute §4 steps 1–5 and §5, followed by §8 steps 3–5 on staging. Re-run Phase 14 afterwards to upgrade this BLOCKED status with real backup, restore, staging, and schema-compatibility evidence — **no production baseline or migration should occur before that evidence exists.**
