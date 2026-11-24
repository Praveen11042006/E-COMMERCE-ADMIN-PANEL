# Phase 12E — Dependency Security Audit and Remediation

## Initial audit summary

Audit date environment:

- Node.js `v24.22.2`
- npm `10.9.0`
- Frontend lockfile: `reactapp/package-lock.json`

The full npm audit found **7 development-only advisories: 5 moderate and 2 critical**. `npm.cmd audit --omit=dev --json` returned **0 vulnerabilities**, so no production dependency is currently affected by these reported advisories.

The first sandboxed audit request could not contact npm’s audit service. It was repeated with approved read-only registry access and returned the results below. No dependency file was changed during Phase 12E.

## Advisory details and dependency paths

| Package | Severity | Vulnerable range | Installed version | Classification and path | Patched resolution |
|---|---:|---|---|---|---|
| `vitest` | Critical | `<=4.1.10`; includes `<3.2.6` arbitrary file read/execution advisory | `3.2.4` | Direct `devDependency` | `4.1.12` or later |
| `tinypool` | Critical | `<=2.1.1` (one advisory `<=2.1.0`) | `1.1.1` | Dev transitive: `vitest@3.2.4 -> tinypool@^1.1.1` | Resolved by patched Vitest 4 line |
| `@vitest/mocker` | Moderate | `<4.1.12` (also `>=2.1.0 <4.1.11`) | `3.2.4` | Dev transitive: `vitest@3.2.4 -> @vitest/mocker@3.2.4` | `4.1.12` or later |
| `sucrase` | Moderate | `3.35.0` (via vulnerable `glob`) | `3.35.0` | Dev transitive: `tailwindcss@3.4.19 -> sucrase@^3.35.0` | `3.35.1` shown by npm dry run |
| `glob` | Moderate | `>=9.0.0 <=14.0.1` | `10.5.0` | Dev transitive: `tailwindcss -> sucrase -> glob@^10.3.10` | Removed/resolved by patched `sucrase` chain |
| `minimatch` | Moderate | `>=7.0.0 <11.0.1` | `9.0.9` under `glob` | Dev transitive: `tailwindcss -> sucrase -> glob -> minimatch@^9.0.4` | Removed/resolved by patched `sucrase` chain |
| `postcss-selector-parser` | Moderate | `<6.1.5` | `6.1.4` | Dev transitive: `tailwindcss@3.4.19` and `postcss-nested@6.2.0` | `6.1.6` shown by npm dry run |

`npm.cmd ls --all` and `npm.cmd explain` were used to verify the installed versions and paths. A separate `minimatch@3.1.5` is used by ESLint, but is outside this advisory’s affected range and is not the audited vulnerable node.

## Remediation planning and decision

`npm.cmd audit fix --dry-run --json` was used as a read-only planning operation; `npm audit fix` and `npm audit fix --force` were not run.

The dry run showed that a non-major Vitest move to `3.2.7` would still leave the Vitest/mocker moderate advisory. The first fully patched Vitest resolution is `4.1.12`. Published Vitest 4.1.12 peer dependencies support the current Vite 7 (`^6 || ^7 || ^8`) and Node 24, so a future upgrade is technically plausible, but it is a **major test-tool upgrade**.

Phase 12E explicitly requires stopping rather than forcing a breaking upgrade. Because resolving both critical advisories and the Vitest mocker advisory requires that major upgrade, no remediation was applied. This also avoids unnecessary lockfile churn while the worktree already contains prior dependency and application changes.

The remaining Tailwind-chain moderate advisories could be addressed separately with compatible patch resolutions (`sucrase 3.35.1`, `postcss-selector-parser 6.1.6`), but doing so alone would leave the critical Vitest chain unresolved. They were not changed in an incomplete remediation pass.

## Files changed

- `PHASE_12E_RESULT.md` only.

No `package.json`, lockfile, source file, backend file, schema, migration, Flyway setting, or production configuration was changed during this phase.

## Measured audit results

Full audit after the read-only assessment:

```powershell
npm.cmd audit --json
```

Result: **7 vulnerabilities — 5 moderate, 2 critical**. All are development-only.

Production-only audit:

```powershell
npm.cmd audit --omit=dev --json
```

Result: **0 vulnerabilities**.

The complete audit is not clean. The production dependency audit is clean.

## Verification not run

No dependencies or source files were changed, so `npm ci`, frontend test/lint/build, and backend Maven tests were not rerun in Phase 12E. Their most recent Phase 12D measured baseline remains frontend 9/9 tests passing, frontend build passing, lint with 0 errors and 6 existing warnings, and backend 127 tests with 0 failures/errors/skips. These prior results are not represented as new Phase 12E verification.

## Remaining risks and blocker

- Two critical advisories remain in the local/CI test toolchain through Vitest 3.2.4 and Tinypool 1.1.1.
- Five moderate advisories remain in Vitest and Tailwind build-tool transitive dependencies.
- None of the seven reported advisories affect the production dependency graph according to `npm audit --omit=dev`.
- A separate approved task should upgrade Vitest to `4.1.12` or later, run the frontend test/lint/build suite, inspect any Vitest 4 migration impact, then reassess the Tailwind-chain patches. No production system, MySQL, Flyway, or external database was accessed.

## Git status and dependency diff summary

Before Phase 12E, `reactapp/package.json` and `reactapp/package-lock.json` were already modified by the prior frontend testing work. They were inspected but not modified in this phase. The project worktree also contains substantial pre-existing modified and untracked work, all preserved.
