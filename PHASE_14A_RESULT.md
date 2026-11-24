# PHASE 14A RESULT — Backup & Staging Readiness Assessment

Date: 2026-11-24
Project: `E:\Subjects\ecomAlmostdone\ecomAlmostdone`
**Assessment status: PASS (assessment complete)** — evidence gathered, prerequisites and blockers identified.
**Phase 14 status: unchanged — BLOCKED.** This report does not complete Phase 14 and makes no release-readiness claim.

**Read-only confirmation:** no database connection was opened, no SQL or Flyway command was executed, no database/user was created, no backup or restore file was written, Docker was not started or modified, and no application/config/SQL/migration file was changed. The only file written is this report.

---

## 1. Current readiness status

**Not ready to execute Phase 14.** Every gating precondition except one is still missing, and the one that changed (the accidental production-default configuration) is now closed.

| Prerequisite | State | Evidence |
|---|---|---|
| Data-bearing backup of `ecom` | **Missing** | Only a schema-only dump exists (§2) |
| Verified restore rehearsal | **Never performed** | No rehearsal artifact, schema or log anywhere |
| Backup automation / retention | **Missing** | No script, no scheduled task, CI runs tests only |
| Credentials for `ecom` | **Missing** | `DB_URL`/`DB_USERNAME` unset; only an orphan `DB_PASSWORD` of unknown target |
| Isolated staging instance | **Missing** | No staging profile/compose/account |
| Staging credentials | **Missing** | `STAGING_DB_URL` unset; `FLYWAY_TEST_DB_*` unset |
| Flyway baseline plan | **Defined, unexecuted** | Guard would reject any staging target (§5) |
| Live schema comparison with `ecom` | **Impossible without access** | Only a static dump↔V1 comparison exists |
| Safe default DB configuration | **Now correct** | Phase 16 removed the `ecom` fallback; base is `validate` |

Good news relative to the Phase 14 report: the base profile no longer defaults to `jdbc:mysql://localhost:3306/ecom` and no longer runs `ddl-auto=update`, so the Phase 13E "accidental live-schema mutation" class of risk is closed for future runs. It does **not** retroactively prove what happened during that incident (§8).

## 2. Existing backup inventory

| Artifact | Facts (measured) |
|---|---|
| `Dump20261031.sql` | 6,516 bytes, 173 lines, `MySQL dump 10.13 Distrib 8.0.40`, header `Host: localhost  Database: ecom` |
| Structure | **7** `CREATE TABLE` statements; **0** `INSERT INTO` statements; **0** `CREATE DATABASE`/`USE`; 0 triggers/routines/events |
| Destructive content | `DROP TABLE IF EXISTS` before each table — a restore over a live schema drops data |
| Age | 2026-10-31 → **~24 days old** |
| Integrity evidence | none (no checksum, no restore rehearsal, no recorded row counts) |
| Other backups in project | **none** — no `.sql`, `.dump`, `.zip` or `.gz` besides this file; no backup/restore scripts (`*.sh`, `*.ps1`) |
| Automation | **none** — no Windows scheduled task matching mysql/backup/dump; `.github/workflows/quality.yml` runs only `npm ci/test/lint/build` and `./mvnw test` |
| Recovery aid that does exist | Binary logging is enabled (`log-bin="LAPTOP-U1BNUAQR-bin"` in `my.ini`), so point-in-time recovery is *theoretically* possible for a DBA with server access — retention and current binlog files could not be verified (see §2.1) |

### 2.1 What could not be verified (and why)

The MySQL data directory (`C:\ProgramData\MySQL\MySQL Server 8.0\Data`) is **not readable by the current user** (`Suresh`): directory listing returns `Permission denied` and `icacls` cannot process it. The service runs under `NT AUTHORITY\NetworkService`. Consequently this assessment **could not** enumerate databases, table files, binlogs, or datadir size from disk. Confirming the existence/state of `ecom`, `ecom_flyway_test`, or any backup-related objects requires either an elevated shell (approval needed) or a MySQL account — neither was used, because connecting would have violated this phase's read-only rule.

## 3. Backup and restore prerequisites

### 3.1 Tooling (present, one PATH caveat)

| Tool | Location | Status |
|---|---|---|
| MySQL Server | `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe` — **8.0.40** | Installed; service `MySQL80` |
| Service binding | `mysqld.exe --defaults-file="C:\ProgramData\MySQL\MySQL Server 8.0\my.ini" MySQL80`, run as `NT AUTHORITY\NetworkService` | Listening on `0.0.0.0:3306` (PID 6824; a second `mysqld` PID 5808 also present) |
| `mysqldump.exe`, `mysql.exe`, `mysqladmin.exe`, `mysqlpump.exe`, `mysqlcheck.exe`, `mysqlbinlog.exe` | same `bin` directory | **Installed but NOT on `PATH`** — must be called by full path (or `PATH` extended) |
| MySQL Shell | `C:\Program Files\MySQL\MySQL Shell 8.0\bin\mysqlsh.exe` — 8.0.40 | On `PATH`; usable for dump/restore via `util.dumpInstance`/`loadDump` |
| Server/dump version parity | dump header 8.0.40 vs server 8.0.40 | Compatible |

### 3.2 Space (measured this phase)

| Drive | Free | Note |
|---|---|---|
| `C:` | **11 GB** of 201 GB (95% used) | Host MySQL + Docker VM live here; tight |
| `D:` | **98 GB** of 151 GB | Best target for backup artifacts |
| `E:` | **85 GB** of 125 GB | Project drive; acceptable for artifacts |

A schema+data dump of this small application is expected in the tens of MB, so space is not the constraint for the backup itself; it is the constraint for a Docker-hosted staging instance (§4).

### 3.3 What the backup must contain (not executed)

1. A dedicated **least-privilege** dump account (`SELECT, SHOW VIEW, TRIGGER, EVENT, LOCK TABLES`, optionally `RELOAD`/`PROCESS`) — not `root`, not the application account.
2. Explicit confirmation that the target is the real `ecom` schema on `localhost:3306` (identity proof required before dumping).
3. `mysqldump --single-transaction --quick --routines --triggers --events --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 --databases ecom` (full path to `mysqldump.exe`).
4. Immediate SHA-256 checksum, byte size, and the dump's own row counts; store off the project tree (e.g. `D:\backups\`), access-restricted.
5. A rehearsal restore into a **new isolated schema** followed by: 7 tables present, per-table `COUNT(*)` equal to source, `CHECKSUM TABLE` equal, `SHOW CREATE TABLE` equal, 2 FKs and 2 unique keys intact, then backend startup with `SPRING_PROFILES_ACTIVE=prod` (`ddl-auto=validate`) to prove entity/schema compatibility.

## 4. Staging infrastructure requirements

**No staging infrastructure exists.** Verified: no `application-staging.properties`, no staging compose override, no `*staging*` file anywhere in the project, no staging account, no staging credential variable. What does exist is reusable raw material:

| Available | Detail |
|---|---|
| Docker images from Phase 13F | `ecom13f-verify-backend` (684 MB), `ecom13f-verify-frontend` (74.8 MB), `mysql:8.0` (1.1 GB) |
| Phase 13F test volume | `ecom13f-verify_mysql_data` (197.9 MB, synthetic smoke data only) |
| Docker engine | Last verified healthy on 2026-11-24 (Phase 13F PASS); **not started in this phase** |

Two viable staging options, both needing approval:

- **Option A — disposable Docker MySQL container** (preferred; no host MySQL involvement): `mysql:8.0`, loopback-only publish (e.g. `127.0.0.1:3310:3306`), database `ecom_staging`, dedicated account `staging_app`. Requires ~1–2 GB free on `C:` for the container layer; current headroom (11 GB) is sufficient but not generous, and Phase 13F showed this host fails hard when `C:` fills. Mitigation: keep the data volume small, run `docker builder prune` first, or relocate Docker's disk image to `E:`.
- **Option B — a second schema on the host MySQL server** (`ecom_staging` on `localhost:3306`): no disk/Docker risk, but it shares the production instance, so it needs an explicit DBA decision plus a dedicated account. It also collides with the Flyway guard's fixed target (§5).

Staging must additionally provide: an inventory of seeded data derived **only** from the verified backup, a `SPRING_PROFILES_ACTIVE=prod` run (validate-only) to prove schema compatibility, and read-only smoke checks. It must never contain real customer/payment data beyond what the rehearsal requires, and must never point at `ecom`.

## 5. Flyway baseline and validation plan

### Current state (verified)

| Item | Value |
|---|---|
| `V1__baseline_schema.sql` | 7 tables (`audit_logs`, `coupons`, `orders`, `payments`, `product`, `users`, `order_item`), InnoDB, utf8mb4 / `utf8mb4_0900_ai_ci`, 2 FKs on `order_item`, unique keys `coupons.code` + `users.email`, `users.role ENUM('ADMIN','USER')`, no DML |
| Flyway dependencies | `flyway-core` + `flyway-mysql`, Boot-managed version 10.20.1 (Phase 11D) |
| Enabled profiles | **only** `flyway-test`; base, `dev`, `prod`, `docker`, H2 `test`, `e2e` all `spring.flyway.enabled=false` |
| `flyway-test` properties | URL/user/password strictly from `FLYWAY_TEST_DB_URL`/`_USERNAME`/`_PASSWORD`; `locations=classpath:db/migration`; `baseline-on-migrate=false`; `ddl-auto=validate` |
| Safety guard | `FlywayTestSafetyGuard` (EnvironmentPostProcessor, `LOWEST_PRECEDENCE`, runs before Flyway beans) rejects: profile combination with `prod`, `flyway.enabled=false`, a non-`jdbc:mysql:` URL, a host other than **`127.0.0.1`**, a port other than **`3306`**, a path other than **`/ecom_flyway_test`**, credentials embedded in the URL, or a username other than **`flyway_test`** |

### Consequence for staging

On a populated database with no `flyway_schema_history`, Flyway refuses to start because `baseline-on-migrate=false`. Adoption therefore **requires an explicit `baseline` operation** (a modifying action, separately authorized). Additionally, the guard's hard-coded target (`127.0.0.1:3306/ecom_flyway_test`, user `flyway_test`) will **reject** any staging database on another port/name. Staging adoption needs either a reviewed guard extension or a dedicated staging Flyway profile — a code change that must be approved, and none was made here.

### Plan (in order, nothing executed)

1. Verified full backup + restore rehearsal (§3) — prerequisite for everything else.
2. Staging instance built **from that backup**, with isolation proven (`SELECT @@hostname, DATABASE(), CURRENT_USER()` all showing staging).
3. Dedicated least-privilege migration account scoped to the staging schema only.
4. `flyway baseline -baselineVersion=1 -baselineDescription="existing schema pre-Flyway"` against **staging only**, then `flyway info` / `validate` to confirm baseline = V1 with no pending migrations.
5. Backend on staging with `SPRING_PROFILES_ACTIVE=prod` → must start under `ddl-auto=validate`; then read-only smoke tests.
6. Only after staging sign-off: repeat inside an approved production change window with rollback ready. Do **not** flip `baseline-on-migrate=true` in production as a shortcut.

## 6. Missing authorization and credentials

| Needed | Current state | Who must provide |
|---|---|---|
| Approval to take a full `mysqldump` of `ecom` | **Not granted** | Database owner |
| Dump account (least privilege) + password | **Absent** | Database owner / DBA |
| Confirmation of target identity (`localhost:3306`, schema `ecom`) | **Unproven** | Database owner |
| Approval to create staging instance + DB + account | **Not granted** | Project owner |
| Staging credentials (`STAGING_DB_URL`/`_USERNAME`/`_PASSWORD`) | **Unset** | Project owner |
| Approval to create/extend a staging Flyway target (guard change) | **Not granted** | Project owner + reviewer |
| Approval to run `flyway baseline` on staging | **Not granted** | Project owner |
| Approval for production baseline/migration window | **Not granted** | Release owner |

Environment reality check (presence only, values never read or printed):

- `DB_URL` **unset**, `DB_USERNAME` **unset**, `DB_PASSWORD` **set** — origin and target unknown; it was deliberately not used and must not be reused without owner confirmation.
- `FLYWAY_TEST_DB_URL` / `_USERNAME` / `_PASSWORD` **all unset**, so the `flyway-test` profile cannot run even if authorized.
- `STAGING_DB_URL` **unset**.
- No `.env` in the project (only `.env.example`, placeholders only); the Phase 13F `.env.verify13f` was deleted after use.
- Neither `ecom_flyway_test` nor a `flyway_test` account can be confirmed to exist (no DB access; datadir unreadable).

## 7. Risks and blockers

### Blockers (must clear before Phase 14 can start)

1. **No data-bearing backup.** The only dump is schema-only, ~24 days old, unprotected by checksum, and destructive on restore (`DROP TABLE`).
2. **No restore rehearsal.** Nobody has proven the dump can be restored or that the app starts against it.
3. **No credentials/authorization** for `ecom`, staging, or Flyway. Target identity cannot be proven, so the safety gate "stop if identity/isolation is unproven" applies.
4. **No staging environment** and no staging account.
5. **Flyway guard blocks any staging target** other than `127.0.0.1:3306/ecom_flyway_test` as `flyway_test`.
6. **No backup automation/retention**, so any backup taken manually is a one-off.

### Risks

- **Unknown live drift:** the dump is dated 2026-10-31; the live `ecom` schema may have changed since, and only a live comparison can tell.
- **Phase 13E incident unresolved:** an E2E backend once ran for ~2 minutes against `jdbc:mysql://localhost:3306/ecom` with `ddl-auto=update`. Phase 16 closed the code path that allowed it, but the historical effect on `ecom` remains unverified — and precisely this is what a pre-adoption backup + `SHOW CREATE TABLE` comparison would settle.
- **Disk pressure on `C:`** (11 GB free, 95% used) is the exact condition that failed Phase 13F; staging in Docker repeats that risk. Backup artifacts belong on `D:`/`E:`.
- **Datadir unreadable without elevation** limits forensic/DB-enumeration work to an approved elevated shell or a MySQL account.
- **Binary-log retention unknown** on a nearly full `C:`; binlogs can consume the remaining space.
- **Baseline is a modifying operation**: doing it on production before a verified backup would be irreversible in practice.
- **Orphan `DB_PASSWORD` env var** of unknown provenance invites accidental use against the wrong database — it must be treated as unsafe.

## 8. Existing schema comparison evidence and outstanding gaps

| Evidence | Result | Scope limitation |
|---|---|---|
| Phase 11D | `V1__baseline_schema.sql` derived from `Dump20261031.sql`; 7 approved tables, no added indexes/FKs/data | Compared to the **dump**, not the live server |
| Phase 14 §6 | Normalized DDL comparison dump vs V1: table set 7/7 identical; columns, types, nullability, AUTO_INCREMENT, PK/UK/FKs, index names, engine, collation all identical (`normalized_identical: true`); 2 FKs, 2 unique keys | Same: static, schema-only, unverified against the current server |
| This phase (re-verified) | Dump still contains 0 `INSERT` statements, 7 `CREATE TABLE`, 0 `CREATE DATABASE`/`USE`; V1 still matches the 7-table/2-FK/2-UK shape | No live comparison performed |

**Outstanding gaps:** live `ecom` DDL vs V1; live row counts/data compatibility; triggers/routines/events in the live server (the dump has none); actual `ecom_flyway_test` contents; any post-2026-10-31 schema change; and the Phase 13E incident's footprint.

## 9. Exact recommended next steps

1. **Get decisions, in writing** (no technical work needed first): (a) approval + least-privilege `ecom` dump account; (b) approval to create an isolated staging database/account; (c) approval to take a full dump and store it at `D:\backups\`; (d) approval for a staging Flyway baseline (guard extension or new staging profile).
2. **Free `C:` headroom** to ≥15 GB (remove the 2.18 GB Phase 13F build cache and, if desired, the 4.0 GB of retained Phase 13F images/volume) so a staging container cannot repeat the read-only-FS failure.
3. **Take the backup** with `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe` using the flags in §3.3, after proving the target is `ecom`; checksum it and move it to `D:\`.
4. **Rehearse the restore** into a new isolated schema; verify counts, `CHECKSUM TABLE`, `SHOW CREATE TABLE`, FK/UK counts; start the backend with `SPRING_PROFILES_ACTIVE=prod` against the rehearsal schema.
5. **Stand up staging** (Option A preferred), restore the verified dump, prove isolation, then run the §5 baseline plan on staging only.
6. **Only then** schedule the production baseline/migration inside a change window with the verified rollback (the backup + rehearsal).
7. **Amend Phase 14 evidence** with the live dump comparison, restore rehearsal logs, staging run and Flyway `info`/`validate` output; Phase 14 stays BLOCKED until those artifacts exist.

## 10. Confirmations and boundaries

- **Original `ecom` database: never contacted.** No connection attempt, no credentials used, no query.
- **No SQL executed, no Flyway command** (`migrate`/`baseline`/`repair`/`clean`/`info`) run; Flyway remains disabled everywhere except the guarded, credential-less `flyway-test` profile.
- **No database or user created**; **no backup or restore file created**, modified or deleted.
- **Docker: not started, not modified**; Phase 13F resources remain exactly as Phase 13F left them.
- **No source, dependency, configuration, SQL or migration file changed** — only `PHASE_14A_RESULT.md` was added. All 175 pre-existing uncommitted/untracked changes are preserved; no destructive Git command was used.
- **No credential, password, token or connection string** was printed, logged or written (environment variables were checked for presence only).
- **Phase 14 remains BLOCKED; Phase 13F remains PASS; Phase 16 remains PASS (application-level).** No production deployment, migration or release-readiness claim is made.
