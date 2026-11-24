# PHASE 14C — Backup Permission Verification (READ-ONLY)

**Date:** 24 November 2026
**Project:** `E:\Subjects\ecomAlmostdone\ecomAlmostdone`
**Scope:** Verify the prerequisites for creating a dedicated MySQL backup account. Read-only.
**Result:** **NOT PASS — checks 1, 4 and 5 (and the live-session part of check 3) were not executed, because no credential is available to this session for running SQL against `localhost:3306`.** Everything derivable from server identity, configuration, the schema migration and the existing dump is recorded below. **Phase 14 remains BLOCKED.**

---

## 1. Restrictions observed in this phase

The following were **not** done:

- no `CREATE USER`, `ALTER USER`, `GRANT`, `REVOKE`, or privilege change of any kind;
- no `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `DROP`;
- no `mysqldump`, `mysql` import, or restore command;
- no Flyway, no Docker;
- no reading or use of the `DB_PASSWORD` environment variable (it exists in the shell environment; it was matched by name only and its value was neither read nor printed);
- no extraction of the password stored by MySQL Workbench, from its connection file or from Windows Credential Manager; no password guessing;
- no creation of directories or files other than this report.

**Credential exposure check:** no secret, password, token, or credential-bearing connection string appears in this report. One statement was executed against the server in this phase: a password-less `root` probe (see §3), which returned `ERROR 1045 (28000): Access denied`.

---

## 2. Check-by-check result summary

| # | Check | Result | Evidence class |
|---|-------|--------|----------------|
| 1 | `SHOW GRANTS FOR CURRENT_USER();` | **NOT EXECUTED** | Requires a credentialed session |
| 2 | Active MySQL account and server identity | **PARTIAL** | Server identity verified; active account inferable only |
| 3 | `ecom` exists + default charset/collation | **PARTIAL** | Existence evidenced; DB-level default **not queried** |
| 4 | List tables in `ecom` | **PARTIAL** | 7 tables from schema sources; live list not queried |
| 5 | Stored procedures/functions, triggers, events | **NOT EXECUTED** (indirect evidence only) | Dump + migration inspection |
| 6 | Minimum privileges needed for a backup | **COMPLETE** (conditional on §7) | Derived from object inventory |
| 7 | `D:\backups\mysql\ecom\` outside project + free space | **COMPLETE** | Filesystem inspection |
| 8 | Recommended least-privilege `ecom_backup` grants | **COMPLETE** (not executed) | Proposal only |

Five of the eight checks are complete or complete-with-evidence. The three that require a live SQL session are blocked by an access gap, not by a technical failure of the server.

---

## 3. Execution capability for this phase (why live SQL did not run)

| Probe | Result |
|---|---|
| `mysql_config_editor print --all` (login paths) | **Empty** — no stored login path exists. |
| `%USERPROFILE%\.my.cnf` / `.mylogin.cnf` | **Absent.** |
| `mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u root` (no password) | `ERROR 1045 (28000): Access denied for user 'root'@'localhost' (using password: NO)` |
| `DB_PASSWORD` in environment | Present, deliberately unused. |
| `DB_USERNAME` in environment | Not set (matched by name only). |

A password-less `root` login is rejected. The only working credentials on this machine are held inside MySQL Workbench (a live GUI session) and in its connection file, which stores the `flyway_test` password. Extracting either would be credential harvesting and is excluded by this phase's restrictions. Consequently **all SQL-dependent checks were left unexecuted rather than approximated from a privileged session.**

**Note on the available session:** a live Workbench session exists as `root@localhost` (connection "Local instance MySQL80", `localhost:3306`, server 8.0.40, opened today 12:27:28). That session *can* run the queries in §9 — see the owner action there.

---

## 4. Check 2 — Server identity and active account

| Item | Value | Source |
|---|---|---|
| Product / version | MySQL Community Server **8.0.40** for Win64 on x86_64 | `mysqld.exe --version`; dump header `Distrib 8.0.40, for Win64 (x86_64)` |
| Windows service | `MySQL80` — **RUNNING** (`WIN32_OWN_PROCESS`, stoppable) | `sc query MySQL80` |
| Process | PID **6824** (owner of listeners below) | `netstat -ano` |
| Listeners | `0.0.0.0:3306`, `[::]:3306`, `0.0.0.0:33060`, `[::]:33060` | `netstat -ano` |
| Established clients | 2 × loopback connections to `[::1]:3306` from PID **26500** (MySQL Workbench) | `netstat -ano` |
| Install root | `C:\Program Files\MySQL\MySQL Server 8.0\bin` | filesystem |
| Config file | `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini` | filesystem |
| Data directory | `C:/ProgramData/MySQL/MySQL Server 8.0\Data` | `my.ini` |
| Binary logging | `log-bin="LAPTOP-U1BNUAQR-bin"`, `server-id=1` | `my.ini` |
| GTID | **Not configured** — `gtid_mode` / `gtid_purged` / `enforce_gtid_consistency` absent from `my.ini`; MySQL 8.0 default is `gtid_mode=OFF` | `my.ini` inspection (not queried live) |
| `lower_case_table_names` | `1` | `my.ini` |
| `secure_file_priv` | `C:/ProgramData/MySQL/MySQL Server 8.0/Uploads` | `my.ini` |
| slow query log / general log | `slow-query-log=1`, `long_query_time=10`; `general-log=0` | `my.ini` |
| Default storage engine | `INNODB` | `my.ini` |
| Charset override in server config | **None** — no `character-set-server` or `collation-server` directive | `my.ini` |

**Active account:** not directly observable without SQL. Two facts constrain it:

1. The Workbench connection file `connections.xml` maps connection "Local instance MySQL80" to `userName = root`, `hostName = localhost`, `serverVersion = 8.0.40`.
2. Today's Workbench SQL-action log shows that this session executed `SELECT user, host FROM mysql.user` (5 rows, 12:29:26) and `SHOW GRANTS FOR 'root'@'localhost'` (3 rows, 12:30:28).

So the live administrative session is consistent with `root@localhost`, but `USER()` / `CURRENT_USER()` were not read in this phase. Treat it as **expected `root@localhost`, unverified**.

**Accounts present on the server (indirect):** `mysql.user` returned 5 rows at 12:29:26. Exact names were not captured. A second connection profile exists for `flyway_test@127.0.0.1:3306` (connection "Flyway Test"), so a `flyway_test` account is expected to exist. No `ecom_backup` account is known to exist.

---

## 5. Check 3 — `ecom` exists; charset and collation

### 5.1 Existence — evidenced

| Evidence | Detail |
|---|---|
| Workbench saved parameter | `DbSqlEditor:LastDefaultSchema = ecom` for connection "Local instance MySQL80" |
| Workbench autosaved schema tree | `active_schema=ecom`, `expanded=ecom:schema` (recorded 12:37 today) |
| Existing dump header | `-- Host: localhost    Database: ecom`, `-- Server version 8.0.40` |
| Schema migration | `springapp/src/main/resources/db/migration/V1__baseline_schema.sql` creates the 7 application tables |

`ecom` exists on this instance and is the schema the Workbench session was last working in.

### 5.2 Charset and collation — table level verified, database default not queried

| Level | Value | Evidence |
|---|---|---|
| All 7 tables | `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` | `Dump20261031.sql` lines 35/58/79/101/121/140/161; identical in `V1__baseline_schema.sql` |
| Table engine | `InnoDB` for all 7 (7 × `ENGINE=InnoDB`) | `V1__baseline_schema.sql`, `Dump20261031.sql` |
| Server-level default | untouched (MySQL 8.0 default `utf8mb4` / `utf8mb4_0900_ai_ci`) | `my.ini` has no charset override |
| **Database-level default** | **NOT QUERIED** | requires `information_schema.SCHEMATA` (see §9) |

**Conclusion:** every known table is `utf8mb4` / `utf8mb4_0900_ai_ci`, so `--default-character-set=utf8mb4` is the correct dump charset and no conversion risk exists for these tables. The database-level default remains unverified; it does not affect a per-table dump of `utf8mb4` tables but should still be recorded in §9's output.

---

## 6. Check 4 — tables in `ecom`

Not listed live. The authoritative object list in version control and in the only existing dump agree exactly:

| # | Table | Engine | Auto-increment seen in sources |
|---|-------|--------|-------------------------------|
| 1 | `audit_logs` | InnoDB | 4 |
| 2 | `coupons` | InnoDB | — |
| 3 | `order_item` | InnoDB | 10 |
| 4 | `orders` | InnoDB | 6 |
| 5 | `payments` | InnoDB | — |
| 6 | `product` | InnoDB | 11 |
| 7 | `users` | InnoDB | 10 |

Sources: `CREATE TABLE` statements in `Dump20261031.sql` (7) and `V1__baseline_schema.sql` (7); the two sets match by name and DDL. `V1__baseline_schema.sql` is the only file in `db/migration/`, so there are no later migrations that could have added tables.

**Caveat:** the live schema could differ from sources if tables were created or dropped by hand outside Flyway. The dump is dated 31 Oct 2026 (schema-only) and the migration 31 Oct 2026. Live confirmation is in §9.

---

## 7. Check 5 — stored procedures/functions, triggers, events

| Object type | Finding | Strength |
|---|---|---|
| Views | **None found** | dump has 0 `CREATE ... VIEW`; migration has none |
| Triggers | **None found** | migration has none; `mysqldump` includes triggers by default and `Dump20261031.sql` contains no `CREATE TRIGGER` / `DELIMITER` |
| Procedures / functions | **None found** | migration has none; the dump was taken **without** `--routines`, so its silence does **not** prove absence — live check required |
| Events | **None found** | migration has none; the dump was taken **without** `--events` — live check required |
| `delimiter` blocks anywhere in migrations | none | grep for `create procedure|create function|create trigger|create event|delimiter` returned nothing |

**Conclusion:** based on all available evidence there are **no routines, triggers, views, or events** in `ecom`. Because routines and events are invisible to the existing dump's options, this conclusion is **provisional until §9 confirms it**.

**Security note (unrelated to the backup plan, but found while inspecting):** the Workbench SQL-action log for this connection contains a **plaintext password** from an earlier `ALTER USER 'flyway_test'@'localhost' IDENTIFIED BY '…'` statement, and `connections.xml` stores a `password` value for the "Flyway Test" profile. The log file is readable by the Windows account. Recommend, as a separate task, restricting/purging that log and removing the stored Workbench password for the test profile. No value from either location was read into this report.

---

## 8. Check 6 / 8 — minimum privileges, and the proposed `ecom_backup` account

### 8.1 Privileges actually required, by object present

A logical dump of `ecom` with `--single-transaction --no-tablespaces --set-gtid-purged=OFF --triggers --default-character-set=utf8mb4` needs:

| Privilege | Scope | Needed? | Why |
|---|---|---|---|
| `SELECT` | `ecom.*` | **Yes** | reads every table |
| `SHOW VIEW` | `ecom.*` | **No** (no views) — keep only if §9 finds views | required to dump view definitions |
| `TRIGGER` | `ecom.*` | **No** (no triggers) — keep only if §9 finds triggers | `--triggers` needs it when triggers exist |
| `EVENT` | `ecom.*` | **No** (no events) — only if `--events` is used | dumping events |
| `SHOW_ROUTINE` | `*.*` (global) | **No** (no routines) — only if `--routines` is used | hides routine bodies on 8.0.20+ |
| `LOCK TABLES` | `ecom.*` | **No** | `--single-transaction` avoids table locks |
| `PROCESS` | global | **No** | avoided by `--no-tablespaces` |
| `RELOAD` / `FLUSH_TABLES` | global | **No** | only needed for `--single-transaction` when `gtid_mode=ON` **and** `gtid_purged=ON\|AUTO`; GTID is not configured here (§4) |
| `FILE` | global | **No** | not used when `--result-file` writes on the client side |

**Minimum set for the objects as they stand: `SELECT` on `ecom.*` — nothing else.**

### 8.2 Recommended account definition (PROPOSED — NOT EXECUTED)

Two variants. Use **A** if §9 confirms no views/triggers/routines/events (the expected case); use **B** if §9 shows any of them.

```sql
-- Variant A — minimal, matches the objects found (expected case)
CREATE USER 'ecom_backup'@'localhost'
  IDENTIFIED BY '<strong-secret-stored-outside-the-repo>';
GRANT SELECT ON ecom.* TO 'ecom_backup'@'localhost';
```

```sql
-- Variant B — only if §9 finds views / triggers / routines / events
GRANT SELECT, SHOW VIEW, TRIGGER ON ecom.* TO 'ecom_backup'@'localhost';
GRANT SHOW_ROUTINE ON *.* TO 'ecom_backup'@'localhost';   -- only if --routines is used
GRANT EVENT ON ecom.* TO 'ecom_backup'@'localhost';       -- only if --events is used
```

Deliberately **not** granted: `ALL PRIVILEGES`, `WITH GRANT OPTION`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `DROP`, `ALTER`, `REFERENCES`, `INDEX`, `EXECUTE`, `CREATE ROUTINE`, `CREATE TEMPORARY TABLES`, `LOCK TABLES`, `PROCESS`, `RELOAD`, `REPLICATION CLIENT`, `FILE`, `SUPER`, `SYSTEM_USER`, `AUDIT_ADMIN`.

Notes for the phase that creates it:

- Host should be `localhost` (the backup runs on this machine over loopback). If the client connects as `127.0.0.1` with `--protocol=TCP`, MySQL still matches `localhost` for the loopback source in most configurations but the host part of the account must match the resolved client host — verify with `SELECT CURRENT_USER();` right after creating it, and use `'ecom_backup'@'127.0.0.1'` instead if it does not match `localhost`.
- `authentication_policy=*,*` is set in `my.ini`, so `caching_sha2_password` is the default plugin; `mysql` 8.0.40 client and `mysqldump` support it natively.
- Store the secret with `mysql_config_editor set --login-path=ecom_backup --host=127.0.0.1 --port=3306 --user=ecom_backup --password` (prompts without echo, writes `%APPDATA%\MySQL\.mylogin.cnf`), or in an ACL-restricted option file outside the project. Never in a script, `.env`, shell history, or a report.
- After creation, capture the effective grants with `SHOW GRANTS FOR 'ecom_backup'@'localhost';` and store the output as evidence.

Because there are no triggers, no views, no routines and (provisionally) no events, the dump flags that follow from this are `--single-transaction --quick --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 --hex-blob`, **without** `--routines` and **without** `--events`, if §9 confirms the object inventory.

---

## 9. Read-only SQL to complete checks 1, 3, 4, 5 (owner action)

Run this block in the live Workbench session (or any session with read access) and paste the output back. It is entirely read-only: `SHOW` / `SELECT` only. It contains no credentials.

```sql
-- 1. active account + server identity (check 1, 2)
SELECT USER() AS connected_account,
       CURRENT_USER() AS authenticated_account,
       @@hostname AS mysql_host,
       @@port AS mysql_port,
       VERSION() AS server_version;

-- 1b. exact privileges of the active account (check 1)
SHOW GRANTS FOR CURRENT_USER();

-- 3. ecom existence + database-level default charset/collation (check 3)
SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'ecom';

-- 4. tables in ecom, with engine and collation (check 4)
SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COLLATION, TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'ecom'
ORDER BY TABLE_NAME;

-- 5. routines (check 5)
SELECT ROUTINE_TYPE, ROUTINE_NAME
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = 'ecom';

-- 5b. triggers (check 5)
SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING, EVENT_MANIPULATION
FROM information_schema.TRIGGERS
WHERE TRIGGER_SCHEMA = 'ecom';

-- 5c. events (check 5)
SELECT EVENT_NAME, STATUS, INTERVAL_VALUE, INTERVAL_FIELD
FROM information_schema.EVENTS
WHERE EVENT_SCHEMA = 'ecom';

-- 5d. routines you can actually see (validates SHOW_ROUTINE need)
SELECT COUNT(*) AS visible_routines FROM information_schema.ROUTINES;

-- 5e. GTID state, confirms whether RELOAD is needed for --single-transaction
SHOW VARIABLES WHERE Variable_name IN
  ('gtid_mode','gtid_purged','enforce_gtid_consistency',
   'character_set_server','collation_server','innodb_file_per_table');

-- 6. size estimate for the destination check
SELECT TABLE_NAME,
       ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS total_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'ecom'
ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC;
```

### 9.1 Interpretation rules (so the next phase is unambiguous)

- If `SHOW GRANTS FOR CURRENT_USER();` returns rows containing `ALL PRIVILEGES ON *.*` (or explicit `SELECT`, `SHOW VIEW`, `TRIGGER`, `EVENT`, `RELOAD`), the account is sufficient for a full dump.
- If `information_schema.ROUTINES` / `TRIGGERS` / `EVENTS` for `ecom` are empty, use **Variant A** (§8.2) and drop `--routines`/`--events`.
- If any routine is visible, `SHOW_ROUTINE` on `*.*` is required to dump its body; if routines exist but are invisible, the count in 5d differs from 5 and `SHOW_ROUTINE` becomes mandatory.
- If `gtid_mode = ON` **and** `gtid_purged` is `ON`/`AUTO`, add `RELOAD` to `ecom_backup` (or `FLUSH TABLES` before the dump). With GTID `OFF` (expected here), no extra privilege is needed.

---

## 10. Check 7 — backup destination and free space

| Item | Value |
|---|---|
| Target path | `D:\backups\mysql\ecom\` |
| Exists? | **Yes** — `D:\backups\mysql\ecom` exists and is **empty** (created 24 Nov 2026 ~13:00, i.e. not by this phase) |
| Outside the project? | **Yes** — project is `E:\Subjects\ecomAlmostdone\ecomAlmostdone` on volume `E:`; target is on volume `D:`. No overlap with the project tree or the repository |
| Outside the MySQL datadir? | **Yes** — datadir is `C:/ProgramData/MySQL/MySQL Server 8.0/Data` on `C:` |
| Volume total / free | `D:` **151 GB** total, **99 GB free** (35% used) |
| Datadir volume free | `C:` 201 GB total, **11 GB free** (95% used) — too tight to host backups safely |
| Second candidate | `E:` 125 GB total, **85 GB free** (33% used) |
| `ecom` actual data size | **Unknown** — `C:/ProgramData/MySQL/MySQL Server 8.0/Data/ecom` is ACL-denied to the Windows account (`Permission denied`), and no SQL size query is available in this phase |
| Size indication | table `AUTO_INCREMENT` values in the dump are 4–11, i.e. tens of rows per table; expected dump size is kilobytes to low megabytes |
| Physical media | Disk 0, NVMe `Samsung MZALQ512HBLU-00BL2`, 476.9 GB, BusType RAID. **`D:` and `E:` are partitions of the same physical device**, and `C:` is too |

**Verdict:** free space is **ample** (99 GB against an expected dump of a few MB; §9 item 6 replaces the estimate with a measured figure). The destination is correctly outside the project and outside the datadir. It is **not** an independent copy: any real backup strategy still needs a copy to a different physical device or external/offsite media.

---

## 11. Final status

| Phase | Status |
|---|---|
| **14C — Backup permission verification (read-only)** | **NOT PASS — INCOMPLETE.** Checks 6, 7 and 8 are complete. Check 2 is partial. Checks 1, 3 (database default), 4 (live) and 5 (live) were not executed because no credential for the server is available to this session; they were **not** worked around with a privileged or harvested credential. |
| **14 — Production DB adoption & staging** | **BLOCKED** — no authorized data-bearing backup, no recorded SHA-256, no restore rehearsal, no dedicated backup account, no staging instance. |

**Blocker, precisely:** this session can identify the server, read its configuration, and inspect schema sources, but cannot authenticate to `localhost:3306`. A password-less `root` login is refused (`ERROR 1045`), no `mysql_config_editor` login path exists, and no `~/.my.cnf` exists. The only functioning credentials live in MySQL Workbench and in a Windows-stored connection profile; extracting them is out of scope for a read-only verification phase.

**To unblock 14C:** run the read-only block in §9 in the live Workbench session (or authorise a login path for a read-only account), and return its output. With that output, checks 1–5 become complete, §8.2's variant is fixed (A or B), and 14C can be marked PASS. Phase 14 stays BLOCKED regardless until: (a) backup authorization, (b) creation of `ecom_backup`, (c) a data-bearing dump with recorded SHA-256, (d) a verified copy to a second physical device, and (e) a successful isolated restore rehearsal with application validation.

**Files changed by this phase:** `PHASE_14C_RESULT.md` only. No directory was created; no project file was modified; no SQL statement other than the single password-less `root` connection probe was executed.
