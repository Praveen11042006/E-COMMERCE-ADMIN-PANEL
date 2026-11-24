# Phase 12D — Frontend Automated Testing and CI Readiness

## Initial audit

The React/Vite frontend had `dev`, `build`, `lint`, and `preview` scripts but no frontend test runner, test dependencies, test setup, or CI workflow. The repository remote is GitHub, so GitHub Actions is appropriate. Authentication is implemented with `Login`, `PrivateRoute`, local storage, and Axios API helpers; frontend API access is centralized in `src/utils/api.js`.

## Tools selected

- Vitest 3.2.4: Vite-compatible unit/component test runner.
- React Testing Library and `user-event`: user-focused React interaction tests.
- `@testing-library/jest-dom`: readable DOM assertions.
- jsdom: browser-like DOM environment without browser automation.

These are development-only dependencies. No existing runtime dependencies or build tooling were upgraded or replaced.

## Files changed or created

- `reactapp/package.json` — `test` and non-interactive `test:run` scripts.
- `reactapp/package-lock.json` — lockfile entries for the testing toolchain.
- `reactapp/vite.config.js` — Vitest jsdom configuration.
- `reactapp/src/test/setup.js` and `reactapp/src/test/test-utils.jsx` — shared setup and router helper.
- `reactapp/src/auth/Login.test.jsx`
- `reactapp/src/components/PrivateRoute.test.jsx`
- `reactapp/src/components/ProductForm.test.jsx`
- `reactapp/src/UserComponents/UserProductList.test.jsx`
- `reactapp/src/UserComponents/PlaceOrder.test.jsx`
- `.github/workflows/quality.yml` — GitHub Actions quality workflow.

## Regression coverage added

- Login rendering, successful admin navigation, and surfaced backend login errors.
- Protected route redirect for anonymous visitors and matching-role rendering.
- Product catalogue rendering/recommendations and product API failure state.
- Admin product creation, including numeric price/stock normalization.
- Checkout/cart add, quantity change, remove, subtotal behavior, submission payload, and API error handling.

All API calls are mocked in frontend tests. They do not connect to any backend, MySQL, or external system.

## Developer workflow

From `reactapp` in PowerShell:

```powershell
npm.cmd run test
npm.cmd run test:run
npm.cmd run lint
npm.cmd run build
```

`test:run` is non-interactive and returns a nonzero process exit code on test failure.

## CI readiness

`.github/workflows/quality.yml` runs for pushes and pull requests:

1. Frontend: checkout, Node 22, `npm ci`, `npm run test:run`, `npm run lint`, and `npm run build`.
2. Backend: checkout, Temurin Java 17, Maven cache, and `./mvnw test` from `springapp`.

It has read-only repository permissions, no deployment step, no production secrets, and no MySQL configuration. The existing backend test configuration uses in-memory H2 with Flyway disabled.

## Measured verification

Frontend:

```powershell
npm.cmd run test:run
```

Result: **5 test files, 9 tests passed, 0 failed**.

```powershell
npm.cmd run lint
```

Result: **0 errors; 6 existing `react-hooks/exhaustive-deps` warnings** in `PlaceOrder.jsx`, `CreateOrder.jsx`, and `ProductList.jsx`. They predate the test setup and were not changed in this phase.

```powershell
npm.cmd run build
```

Result: **passed** (`vite build` completed successfully).

Backend:

```powershell
.\mvnw.cmd test
```

Result: **127 tests across 21 Surefire reports; 0 failures, 0 errors, 0 skipped**. This ran only against the configured in-memory H2 test datasource with Flyway disabled; no MySQL, original ecom, production service, migration, or external DDL/DML was used.

## Dependency and risk notes

- The npm installation reported 7 transitive-package audit advisories (5 moderate, 2 critical). No automated remediation or dependency upgrades were performed because that is outside this phase’s approved scope; they should be reviewed separately with `npm audit` and upgrade impact assessment.
- Component coverage is deliberately focused. Deferred flows include admin user edit/error UI, full order list filtering/pagination, payment/invoice UI, logout UI (no dedicated logout control was found in the inspected components), and browser-level end-to-end tests.
- The lint warnings remain a maintainability risk but are not CI failures under the existing ESLint configuration.

## Final Git status summary

The worktree already contained substantial modified and untracked frontend/backend work before Phase 12D. This phase added only the test toolchain/configuration, tests listed above, the CI workflow, and this report; existing user changes were preserved. `git diff --check` still reports pre-existing whitespace findings in unrelated files, including `README.md`, `App.jsx`, `CorsConfig.java`, and `OrderRepository.java`.
