# Phase 11A Result — Production Secrets and Configuration

## Configuration hardening

The production profile now requires the following runtime environment variables
without fallback values:

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET`
- `APP_CORS_ORIGINS`

Development defaults remain in `application.properties` and are explicitly
labeled as local-development fallbacks. The production Docker image sets
`SPRING_PROFILES_ACTIVE=prod` and contains no secret values.

Production startup validates nonblank required values, a `jdbc:mysql:` database
URL, a JWT secret of at least 32 UTF-8 bytes, and explicit HTTPS CORS origins.
Wildcard, null, localhost, HTTP, blank, whitespace-padded, and malformed CORS
origins are rejected. Errors name configuration keys only and do not include
their values.

JWT key validation now runs during application startup and uses the same shared
byte-length validation as token signing, preserving issuer, algorithm, expiry,
and token behavior.

## Tests

`ProductionConfigurationValidatorTest` adds 10 focused tests covering missing
required values, short JWT secrets, invalid JDBC URLs, unsafe/malformed CORS
origins, valid HTTPS origins, and error-message secret redaction. The existing
H2 application-context tests continue to pass.

## Verification

| Check | Result |
| --- | --- |
| `mvn test` | Passed: 102 tests, 0 failures, 0 errors, 0 skipped (17 reports) |
| `npm.cmd run lint` | Passed: 0 errors, 6 existing `react-hooks/exhaustive-deps` warnings |
| `npm.cmd run build` | Passed: Vite 7.3.7, 2,529 modules transformed in 5.90 s |
| `npm.cmd audit --omit=dev` | Passed: 0 vulnerabilities |

## Documentation and ignore rules

`README.md` documents `SPRING_PROFILES_ACTIVE=prod`, the mandatory variables,
and secure deployment expectations. Root `.gitignore` now ignores `.env`,
`.env.*`, and local secret-bearing property file patterns without ignoring the
tracked application configuration files.

## Remaining risks

This phase intentionally retains development fallback values for local use.
Production deployments must inject all mandatory variables through their secret
manager or runtime environment. No database schema, Hibernate `ddl-auto`, JWT
lifecycle, frontend token storage, dependency, or credential-rotation changes
were made.
