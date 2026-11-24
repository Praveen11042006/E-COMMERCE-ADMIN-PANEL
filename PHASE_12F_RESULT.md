# Phase 12F — Development Dependency Security Remediation

## Status: PASS

---

## Initial state (confirmed by audit)

Phase 12F had NOT been implemented. Vitest was still at `3.2.4` with all 7
advisories from Phase 12E still present.

| Package | Severity | Installed | Advisory |
|---|---|---|---|
| `vitest` | Critical | 3.2.4 | Arbitrary file read/execution |
| `tinypool` | Critical | 1.1.1 | Transitive via vitest |
| `@vitest/mocker` | Moderate | 3.2.4 | Transitive via vitest |
| `sucrase` | Moderate | 3.35.0 | Via tailwindcss chain |
| `glob` | Moderate | 10.5.0 | Via sucrase |
| `minimatch` | Moderate | 9.0.9 | Via glob |
| `postcss-selector-parser` | Moderate | 6.1.4 | Via tailwindcss |

---

## Compatibility assessment

Before upgrading, the following was verified:

| Item | Value | Compatible with vitest 5? |
|---|---|---|
| Node.js | v24.22.2 | Yes — vitest 5 requires `^22.0.0 \|\| >=24.0.0` |
| Vite | 7.3.7 | Yes — vitest 5 requires `^6.4.0 \|\| ^7.0.0 \|\| ^8.0.0` |
| React | 19.1.1 | Yes — no peer dep conflict |
| `@testing-library/react` | 16.3.0 | Yes — no peer dep conflict |
| `@testing-library/jest-dom` | 6.6.3 | Yes — import path unchanged |
| jsdom | 26.1.0 | Yes — vitest 5 accepts `*` for jsdom |

Vitest 5.1.1 replaces `tinypool` with `tinyexec` internally, eliminating the
tinypool advisory entirely without any configuration change.

---

## Changes made

### Step 1 — Upgrade vitest

`reactapp/package.json`:
```diff
-    "vitest": "^3.2.4"
+    "vitest": "^5.1.1"
```

```
npm install vitest@5.1.1 --save-dev
```

Result: added 1 package, removed 21 packages (tinypool and related), changed 12.
Remaining: 4 moderate advisories (Tailwind chain).

### Step 2 — Resolve Tailwind chain advisories

```
npm audit fix
```

This applied two patch-level changes:
- `sucrase 3.35.0 → 3.35.1` (removes glob dependency)
- `postcss-selector-parser 6.1.4 → 6.1.6`

Result: removed 32 packages (glob, minimatch, and related), changed 2.
**0 vulnerabilities remaining.**

### Step 3 — Verify lockfile consistency

```
npm ci
```

Result: clean install from updated lockfile, 0 vulnerabilities.

---

## Files changed

- `reactapp/package.json` — vitest version bumped to `^5.1.1`
- `reactapp/package-lock.json` — lockfile updated by npm

No source files, backend files, test logic, application configuration, database
schema, migration, or production configuration was changed.

---

## Measured validation results

### npm ci
```
added 361 packages — found 0 vulnerabilities
```

### Frontend tests (vitest run)
```
Test Files  5 passed (5)
      Tests  9 passed (9)
   Duration  3.79s
```
Running on Vitest v5.1.1. All 9 tests pass without modification.

### Frontend lint
```
0 errors, 6 warnings (pre-existing react-hooks/exhaustive-deps — unchanged)
```

### Frontend build
```
vite v7.3.7 — built in 5.08s
dist/index.html                0.46 kB
dist/assets/index-BtZNCA18.js  284.83 kB (gzip: 94.42 kB)
dist/assets/AdminDashboard-...  468.37 kB (gzip: 128.27 kB)
```
Build passes.

### npm audit (full)
```
found 0 vulnerabilities
```

### npm audit --omit=dev (production only)
```
found 0 vulnerabilities
```

### Final installed versions
```
vitest@5.1.1
  └── @vitest/mocker@5.1.1
tailwindcss@3.4.19
  ├── sucrase@3.35.1
  └── postcss-selector-parser@6.1.6
tinypool — REMOVED (replaced by tinyexec inside vitest 5)
```

---

## Database impact

None. No database was accessed or modified.

---

## Remaining issues

None. All 7 previously identified advisories are resolved:
- 2 critical (vitest/tinypool) — resolved by vitest 5.1.1
- 3 moderate (vitest mocker, glob, minimatch) — resolved by vitest 5.1.1 and
  sucrase patch
- 2 moderate (sucrase, postcss-selector-parser) — resolved by `npm audit fix`

The 6 pre-existing `react-hooks/exhaustive-deps` lint warnings remain. They are
not security issues and were not introduced by this phase.
