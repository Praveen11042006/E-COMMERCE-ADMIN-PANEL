# PHASE 14B — Backup Preflight (READ-ONLY)

**Date:** 2026-11-24
**Scope:** Read-only assessment to prepare a safe full logical backup of the local MySQL `ecom` database.
**Result:** **PASS (assessment complete).** No backup was taken. **Phase 14 remains BLOCKED.**

---

## 1. Scope, method, and prohibitions observed

This phase inspected server identity, storage, tooling, privileges, and restore options. It did **not**:

- execute `mysqldump`, `mysql` backup, or restore commands;
- execute SQL that changes data, schema, accounts, or privileges;
- create users or change grants;
- run Flyway or Docker;
- read, print, or use the unknown `DB_PASSWORD` environment variable;
- print passwords, tokens, or credential-bearing connection strings;
- modify source, configuration, migrations, or existing project files.

One credential-free read-only connection probe was made as `root` with no password. MySQL rejected it (`ERROR 1045 ... using password: NO`). No password guessing was attempted, and no stored credential was extracted from Workbench or Windows Credential Manager. The report file is the only file created by this phase.

---

## 2. MySQL server identity and `ecom` existence

| Check | Evidence | Conclusion |
|---|---|---|
| Windows service | `sc query MySQL80` → `STATE: 4 RUNNING`, `WIN32_OWN_PROCESS` | Server is running. |
| Listener | `netstat -ano` → `0.0.0.0:3306` and `[::]:3306` LISTENING, PID **6824**; `:33060` also listening | MySQL classic protocol on 3306, X protocol on 33060. |
| Server binary | `mysqld.exe --version` → `8.0.40 for Win64 on x86_64 (MySQL Community Server - GPL)` | MySQL Community Server 8.0.40. |
| Install path | `C:\Program Files\MySQL\MySQL Server 8.0\bin` | Client tools and server are co-located. |
| Config file | `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini` → `datadir=C:/ProgramData/MySQL/MySQL Server 8.0\Data`, `port=3306` | Confirms the live instance and data root. |
| Hostname | Binary-log basename `LAPTOP-U1BNUAQR-bin` in `my.ini` | Host is inferred as `LAPTOP-U1BNUAQR`; `@@hostname` was not captured by this assessment. |
| Binary logging | `log-bin="LAPTOP-U1BNUAQR-bin"`, `server-id=1` | Binary logging is enabled. This does not replace a logical backup. |
| `ecom` database | Workbench opened connection **"Local instance MySQL80"** (`localhost:3306`, user `root`, server 8.0.40) at **12:27:28 today**; saved parameter `DbSqlEditor:LastDefaultSchema = ecom`; autosaved schema tree at **12:37** has `active_schema=ecom` and `expanded=ecom:schema` | `ecom` exists and is the schema currently in use. |
| Historical confirmation | `Dump20261031.sql` header: `Host: localhost    Database: ecom`, server 8.0.40 | Prior schema-only export was taken from `ecom`. |
| Live administrative reads | Workbench SQL-action log today: `SELECT user, host FROM mysql.user` returned **5 rows** (12:29:26); `SHOW GRANTS FOR 'root'@'localhost'` returned **3 rows** (12:30:28) | The root session is live and can read server account metadata. |

**Limitation:** The data directory `C:\ProgramData\MySQL\MySQL Server 8.0\Data` is ACL-denied to the current Windows user (`icacls` and `ls` both return access denied), so this assessment could not enumerate schema files on disk. The catalog evidence above is from the live Workbench session, not from a direct `SHOW DATABASES` executed by this assessment. If a literal catalog confirmation is required, the owner can run this read-only statement in the already-open Workbench session:

```sql
SHOW DATABASES LIKE 'ecom';
```

No query was run by this phase that could change state.

---

## 3. Database character set and collation

| Layer | Evidence | Value |
|---|---|---|
| Server default | `my.ini` has no active `character-set-server` or `collation-server` override (the setting is commented out); MySQL 8.0 default applies | `utf8mb4` / `utf8mb4_0900_ai_ci` (effective values should be confirmed with `SHOW VARIABLES`; not captured here) |
| All 7 `ecom` tables | Every `CREATE TABLE` in `Dump20261031.sql` ends with `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| Baseline migration | `V1__baseline_schema.sql`: all 7 tables end with `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` | `utf8mb4` / `utf8mb4_0900_ai_ci` |
| Database default | Not directly queried by this assessment | **Unconfirmed**; table evidence and V1 strongly indicate `utf8mb4` / `utf8mb4_0900_ai_ci` |

**Implication:** Take the dump with `--default-character-set=utf8mb4`. Do not rely on the old dump's `/*!50503 SET NAMES utf8 */` line; the tables are `utf8mb4`, and a connection using `utf8` (3-byte) can corrupt or reject 4-byte characters during restore.

Read-only confirmation query for the owner (does not modify anything):

```sql
SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'ecom';
```

---

## 4. Backup tooling inventory

| Tool | Version / location | Status |
|---|---|---|
| `mysqldump.exe` | 8.0.40, `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe` | Present; **not on `PATH`** |
| `mysql.exe` | 8.0.40, same `bin` directory | Present; not on `PATH` |
| `mysqladmin.exe` | 8.0.40, same `bin` directory | Present; not on `PATH` |
| `mysqlsh.exe` | 8.0.40, `C:\Program Files\MySQL\MySQL Shell 8.0\bin\mysqlsh.exe` | Present and on `PATH`; separate install from the server |
| SHA-256 | `sha256sum` (Git for Windows), `certutil`, PowerShell `Get-FileHash -Algorithm SHA256` | Present |
| Compression/archive | GNU `tar` 1.35, `gzip` | Present |
| 7-Zip | `C:\Program Files\7-Zip\7z.exe` | Not installed |

`mysqldump` 8.0.40 matches the server version, so no client/server version-skew issue for option syntax.

---

## 5. Free space on candidate volumes

| Volume | Free | Total | Used | Notes |
|---|---:|---:|---:|---|
| C: | **10.69 GB** | 200.1 GB | ~95% | Too full; do not use for the backup. |
| D: | **98.87 GB** | 150.1 GB | ~34% | Recommended destination. |
| E: | **84.65 GB** | 124.52 GB | ~32% | Project volume; usable but not preferred. |

**Physical layout:** `Get-Partition -DriveLetter D,E` reports both on **Disk 0**, an NVMe `SAMSUNG MZALQ512HBLU-00BL2` (476.9 GB, BusType RAID). D: and E: are therefore partitions of the **same physical device**, not independent media. A copy on D: protects against logical corruption, accidental deletion, and application mistakes, but not against disk failure, ransomware, or theft.

The exact size of `ecom` could not be measured because the data directory is ACL-denied. There is ample headroom on D: for a normal logical dump of this application, but the actual dump size must be checked after creation.

---

## 6. Root account privileges for a full logical backup

**What was confirmed read-only:**

- The live Workbench session authenticated as `root@localhost` and is connected today.
- That session successfully read `mysql.user` (5 rows) and executed `SHOW GRANTS FOR 'root'@'localhost'` (3 rows returned).
- This demonstrates administrative read access to server metadata. The three grant rows are consistent with a standard MySQL 8 administrative `root@localhost` grant set.

**What was not captured:** Workbench's SQL-action log records the statements and row counts, but **not the grant text**. Therefore the exact privileges were not independently verified by this assessment. That is a recorded evidence gap, not a reason to use root for the backup.

**Privileges actually needed by the proposed `mysqldump` command:**

| Requirement | Privilege | Needed here? |
|---|---|---|
| Read table data | `SELECT` on `ecom.*` | Yes |
| Dump views | `SHOW VIEW` on `ecom.*` | Yes |
| Dump triggers | `TRIGGER` on `ecom.*` | Yes |
| Dump Event Scheduler events | `EVENT` on `ecom.*` | Yes, because `--events` is proposed |
| Dump stored routines | global `SHOW_ROUTINE` (MySQL 8.0.20+) | Yes, because `--routines` is proposed |
| Consistent InnoDB snapshot | none extra with `--single-transaction` | `LOCK TABLES` not needed |
| Tablespace metadata | `PROCESS` unless `--no-tablespaces` | Avoided with `--no-tablespaces` |
| Binlog coordinates / flush | `RELOAD` / `FLUSH_TABLES` with `--source-data` or certain GTID states | Not used; see GTID caveat below |

**Conclusion:** Root is **practically sufficient** for this dump, and the live session shows it has broad administrative access. Before execution, capture the exact text with `SHOW GRANTS FOR CURRENT_USER();` and confirm it includes `SELECT`, `SHOW VIEW`, `TRIGGER`, `EVENT`, and `SHOW_ROUTINE`. Do not use root for the actual backup when a dedicated account is available.

**GTID caveat:** `gtid_mode` was not queried. On MySQL 8.0.32+, if `gtid_mode=ON` **and** `gtid_purged=ON|AUTO`, `--single-transaction` requires `RELOAD` or `FLUSH_TABLES`. The proposed command uses `--set-gtid-purged=OFF` and no `--source-data`; if the grant check shows GTID is enabled in that state, add `RELOAD` to the backup account or adjust the command after review.

---

## 7. Recommended dedicated least-privilege backup account (not created)

Create a local-only account, scoped to `ecom` plus the one global dynamic privilege needed for routines:

```sql
CREATE USER 'ecom_backup'@'localhost'
  IDENTIFIED BY '<generate-a-long-random-secret-and-store-it-in-a-vault>';

GRANT SELECT, SHOW VIEW, TRIGGER, EVENT
  ON `ecom`.*
  TO 'ecom_backup'@'localhost';

-- Required only because the proposed dump includes --routines.
GRANT SHOW_ROUTINE ON *.*
  TO 'ecom_backup'@'localhost';
```

Notes:

- `localhost` is the matching host for the local Workbench/server path. If the client is forced to TCP `127.0.0.1` and MySQL does not resolve it to `localhost`, create the account for `'ecom_backup'@'127.0.0.1'` instead; verify with `SELECT CURRENT_USER();`.
- No `ALL PRIVILEGES`, no `WITH GRANT OPTION`, no `CREATE`, `DROP`, `ALTER`, `INSERT`, `UPDATE`, `DELETE`, `FILE`, `PROCESS`, or `RELOAD`.
- If `information_schema.ROUTINES` shows no routines for `ecom`, drop `--routines` from the dump and omit `SHOW_ROUTINE`. If there are no events, drop `--events` and omit `EVENT`. Do not grant what the dump does not use.
- Store the password outside the repository. Preferred: `mysql_config_editor set --login-path=ecom_backup --host=127.0.0.1 --port=3306 --user=ecom_backup --password`, which prompts without echoing and writes `%APPDATA%\MySQL\.mylogin.cnf` (obfuscated, not encrypted; restrict its ACL). Alternatively use an ACL-restricted option file outside the project. Never put the secret in a script, `.env`, shell history, or this report.
- Rotation: record the creation date and rotate after the restore rehearsal or on any suspected exposure.

---

## 8. Recommended backup destination

**Primary:** `D:\backups\mysql\ecom\`

- D: has **98.87 GB free** and is not the nearly-full C: volume.
- Keep the directory outside the Git worktree and outside the MySQL data directory.
- Restrict ACL to the backup operator and Administrators; do not sync it to a public cloud folder by default.
- Use a timestamped filename, for example `ecom_full_20261124_HHMMSS.sql`, plus a `.sha256` sidecar and a small `.txt` manifest (server version, schema, command, start/end time, exit code, byte size, hash).

**Secondary copy:** Because D: and E: are partitions of the same NVMe device, copy the verified dump and its hash to a different physical device (external SSD/HDD) or an approved encrypted off-site location. Do not call the D: copy a disaster-recovery backup until that second copy exists.

**Do not use:** C: (10.69 GB free, ~95% used), the project directory, `C:\ProgramData\MySQL\...\Data`, or any path that a future `git clean`/archive step could include.

Suggested layout:

```text
D:\backups\mysql\ecom\
  2026-11-24\
    ecom_full_20261124_HHMMSS.sql
    ecom_full_20261124_HHMMSS.sql.sha256
    ecom_full_20261124_HHMMSS.manifest.txt
```

---

## 9. Proposed full-backup command (NOT executed)

Review and authorize before running. It reads `ecom` only, takes a consistent InnoDB snapshot, includes routines/triggers/events, avoids tablespace metadata, and writes directly to D: with UTF-8-safe settings.

```powershell
$ts   = Get-Date -Format "yyyyMMdd_HHmmss"
$dest = "D:\backups\mysql\ecom\$((Get-Date).ToString('yyyy-MM-dd'))"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" `
  --login-path=ecom_backup `
  --host=127.0.0.1 --port=3306 `
  --single-transaction --quick `
  --routines --triggers --events `
  --no-tablespaces `
  --set-gtid-purged=OFF `
  --default-character-set=utf8mb4 `
  --hex-blob `
  --result-file="$dest\ecom_full_$ts.sql" `
  ecom

$exit = $LASTEXITCODE
if ($exit -ne 0) { throw "mysqldump failed with exit code $exit" }
Get-Item "$dest\ecom_full_$ts.sql" | Select-Object FullName, Length, LastWriteTime
```

Why these options:

- `--single-transaction` + `--quick`: consistent snapshot for InnoDB without holding `LOCK TABLES` for the whole dump; streams rows instead of buffering.
- `--routines --triggers --events`: includes objects a table-only dump would miss. Drop any of these (and the corresponding grant) if the object type does not exist.
- `--no-tablespaces`: avoids the global `PROCESS` requirement introduced in MySQL 8.0.21.
- `--set-gtid-purged=OFF`: prevents GTID statements that can fail on a restore target; the dump is for logical restore, not replication seeding.
- `--default-character-set=utf8mb4`: preserves 4-byte characters.
- `--hex-blob`: safe representation of binary columns, if any.
- `--result-file`: avoids PowerShell `>` output, which would write UTF-16 and corrupt the SQL file.
- No `--databases ecom`: the dump then contains no `CREATE DATABASE`/`USE`, so it can be restored into an **isolated, differently named** schema during rehearsal without touching `ecom`.

**Preconditions before running:**

1. Written authorization to read `ecom` and write the dump to D:.
2. Backup account created and its grants captured (`SHOW GRANTS`).
3. Confirmed `ecom` charset/collation and table list via read-only queries.
4. Confirmed no routines/events if omitting those options, or `SHOW_ROUTINE`/`EVENT` granted if keeping them.
5. At least 98 GB free on D: (already true) and a second physical destination identified.
6. The command's `--login-path` exists and connects; test with `mysql --login-path=ecom_backup -e "SELECT CURRENT_USER(), DATABASE();"` (read-only) before the dump.
7. Dump output path is new; never overwrite an existing backup.

**After running:** record exit code 0, byte size, start/end time, and any warnings. A zero exit code with a truncated/empty file is still a failure; validate the file structure before hashing.

---

## 10. SHA-256 verification procedure

Generate the hash immediately after the dump finishes, then verify it again after any copy.

**PowerShell:**

```powershell
$file = "$dest\ecom_full_$ts.sql"
$hash = (Get-FileHash -Algorithm SHA256 -Path $file).Hash.ToLower()
"$hash  $(Split-Path $file -Leaf)" |
  Set-Content -Encoding ascii -NoNewline "$file.sha256"
$hash
```

**Git Bash equivalent:**

```bash
cd /d/backups/mysql/ecom/2026-11-24
sha256sum ecom_full_20261124_HHMMSS.sql > ecom_full_20261124_HHMMSS.sql.sha256
sha256sum -c ecom_full_20261124_HHMMSS.sql.sha256
```

**Verification rules:**

1. Hash the file **after** `mysqldump` closes it and the exit code is 0.
2. Store the `.sha256` sidecar next to the dump; also record the hash in the manifest and in the phase report.
3. After copying to the secondary device, run `Get-FileHash`/`sha256sum` again on the copy and compare both values exactly.
4. Before restore, verify the hash again. A mismatch means stop; do not import.
5. SHA-256 detects accidental corruption and transfer errors. It does **not** prove authenticity or protect against deliberate tampering; restrict ACLs, store a copy of the hash separately, and consider a signed manifest if the backup leaves the machine.

**Structural checks beyond the hash:**

- File is non-empty and contains the expected header (`MySQL dump`, server 8.0.40, `Database: ecom` if `--databases` was used).
- Exactly **7** `CREATE TABLE` statements for the known baseline objects (`audit_logs`, `coupons`, `order_item`, `orders`, `payments`, `product`, `users`).
- `-- Dumping routines` / `-- Dumping events` sections present if those options were used; empty sections are acceptable only after confirming the source has none.
- `INSERT INTO` statements exist for data-bearing tables; do not accept a schema-only file as a data backup. The existing `Dump20261031.sql` is **6,516 bytes, 173 lines, 7 CREATE TABLE, 0 INSERT** — it is schema-only and is **not** a valid data backup.

---

## 11. Restore rehearsal into an isolated test environment

Do this only after the backup is created, hashed, and authorized. It must not touch `ecom` or the production data directory.

### 11.1 Isolated target

Use a separate MySQL 8.0.40 instance with its own data directory and port, for example a Docker container:

```bash
docker run --name ecom-restore-rehearsal \
  -e MYSQL_ROOT_PASSWORD='<ephemeral-secret>' \
  -p 127.0.0.1:3307:3306 \
  -v ecom_restore_rehearsal_data:/var/lib/mysql \
  -d mysql:8.0.40
```

- Bind only to `127.0.0.1`; do not publish 3306.
- Do not mount or point at `C:\ProgramData\MySQL\MySQL Server 8.0\Data`.
- If Docker is not used, use any separate MySQL 8.0.40 instance; the isolation requirement is a distinct data directory, port, and schema.
- Do not reuse the Phase 13F verify volume or database. This is a new, clearly named rehearsal.

### 11.2 Create the target schema

```sql
CREATE DATABASE ecom_restore_20261124
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

### 11.3 Verify and import

```bash
# 1. Verify the dump hash first.
sha256sum -c ecom_full_20261124_HHMMSS.sql.sha256

# 2. Import into the isolated schema.
"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
  --host=127.0.0.1 --port=3307 --user=root --password \
  ecom_restore_20261124 \
  < ecom_full_20261124_HHMMSS.sql
```

Do not import the old `Dump20261031.sql` into `ecom`: it contains `DROP TABLE IF EXISTS` statements and no data.

### 11.4 Validate the restore

Run on the **rehearsal** instance (3307), not on 3306:

1. `SHOW TABLES;` → exactly the 7 expected tables.
2. Compare `SHOW CREATE TABLE` for each table with the source's definitions (column types, nullability, defaults, indexes, FKs, charset/collation).
3. Compare row counts per table with read-only `SELECT COUNT(*)` queries on the source `ecom`.
4. Compare `CHECKSUM TABLE` values where practical; differences in physical row order or `AUTO_INCREMENT` state must be explained.
5. Confirm foreign keys and unique keys exist and validate (`SHOW CREATE TABLE`, then a few join/duplicate probes).
6. Confirm no import errors and no `ERROR 1273`/charset warnings; check the client exit code.
7. Start the Spring app against the rehearsal schema with the **prod** profile and explicit env vars:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `DB_URL=jdbc:mysql://127.0.0.1:3307/ecom_restore_20261124?...`
   - `DB_USERNAME` / `DB_PASSWORD` for the rehearsal instance only
   - `JWT_SECRET`, `APP_CORS_ORIGINS`
   - Flyway must remain disabled in prod; Hibernate `validate` must start without drift.
8. Exercise read-only smoke paths (health, login with a seeded test account, product list, order list) and confirm no writes go to the real `ecom`.
9. Record the evidence (commands, timestamps, counts, hashes, screenshots or logs) in the Phase 14 completion report.
10. Only after the evidence is captured and the owner approves, stop and remove the rehearsal container and its volume. Do not remove the verified dump.

**Important:** The rehearsal must not use the Spring `prod` profile against the real `ecom` database. Isolate by port, schema name, and, where possible, a separate MySQL instance.

---

## 12. Risks, unknowns, and go/no-go gates

| Item | Status / risk |
|---|---|
| Data-bearing backup | **Missing.** `Dump20261031.sql` is schema-only (0 `INSERT`) and ~24 days old. |
| Backup authorization | **Not granted.** No account creation or dump was authorized/performed. |
| Restore rehearsal | **Not performed.** No evidence that the dump restores cleanly or that the app validates against it. |
| Exact `ecom` size | **Unknown.** Data directory ACL-denied; no size query. D: has 98.87 GB free, but confirm after the dump. |
| Database-level charset/collation | **Not directly queried.** Table-level evidence is consistently `utf8mb4` / `utf8mb4_0900_ai_ci`. |
| Root grant text | **Not captured.** Workbench shows 3 grant rows; exact privileges need `SHOW GRANTS` before execution. |
| Dedicated account | **Not created.** Grants proposed above; do not use root for the production dump if avoidable. |
| Backup automation | **None found.** No scheduled task or script; this would be a one-off manual backup. |
| Second physical copy | **Missing.** D: and E: are the same NVMe device. |
| Binary logs | Enabled (`log-bin=LAPTOP-U1BNUAQR-bin`), but no retention/purge policy or PITR runbook was verified. Binlogs are not a substitute for this dump. |
| Staging / Phase 14 | Still **BLOCKED**: no staging instance, account, `STAGING_DB_URL`, or authorization; the Flyway guard remains pinned to `ecom_flyway_test`. |
| Phase 13E effect on `ecom` | Still unproven from Phase 14A; a fresh dump is the safest way to establish the current state. |

**Go/no-go gates before any dump:**

1. Owner authorizes read access to `ecom` and writes to `D:\backups\mysql\ecom\`.
2. `SHOW GRANTS` confirms the chosen account has exactly the privileges in §7 (or root's grants are captured and accepted).
3. Read-only queries confirm `ecom` exists, its default charset/collation, the 7 table names, and whether routines/events exist.
4. A second physical destination (external/off-site) is identified for the verified copy.
5. The dump command is reviewed with a fresh timestamp and a non-existing output path.

**Phase 14 remains BLOCKED** until (a) backup authorization, (b) a successful data-bearing dump with recorded SHA-256, and (c) a successful isolated restore rehearsal with app validation are all complete and documented.

---

## 13. Final status

| Phase | Status |
|---|---|
| **14B — Backup preflight (read-only)** | **PASS** — assessment complete; no changes made. |
| **14 — Production DB adoption & staging** | **BLOCKED** — no authorized data backup, no restore rehearsal, no staging. |

**Assessment limitation to carry forward:** the exact root grant text was not captured (only the fact that `SHOW GRANTS` returned 3 rows). Treat the root-privilege conclusion as provisional until the owner captures `SHOW GRANTS FOR CURRENT_USER();` read-only. This does not block the preflight; it blocks relying on root for the dump without that check.
