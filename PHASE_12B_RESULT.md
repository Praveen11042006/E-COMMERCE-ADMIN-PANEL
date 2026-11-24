# Phase 12B — User Management API Validation Hardening

## Original issue and root cause

`PUT /api/users/{id}` accepted the JPA `User` entity directly. This made the API contract depend on entity fields and allowed clients to submit identifiers, roles, activation state, timestamps, and any future writable entity property. The service updated name/email/password only, but there was no request validation, safe duplicate-email response, or explicit update semantics. The admin UI also posted its complete user response back to the endpoint.

## Changes made

- Added `AdminUserUpdateRequest`, an explicit update contract for `PUT /api/users/{id}`.
- Updated `UserController` to require `@Valid AdminUserUpdateRequest` while preserving the route, HTTP verb, and `User` response shape.
- Updated `UserService` to validate its boundary, normalize email addresses, return a 404 for a nonexistent user, detect case-insensitive duplicate emails, and translate a race-time unique constraint failure into a safe conflict response.
- Added `UserRepository.existsByEmailIgnoreCaseAndIdNot` for duplicate checks that exclude the user being updated.
- Added the `DuplicateEmailException` handler: `409 {"message":"Email already exists"}`.
- Updated the admin user-details UI to submit only `name`, `email`, `active`, and an optional new `password`; it no longer posts entity/audit/role fields.
- Added `UserControllerValidationTest` with six API regression tests.

## DTO and validation rules

Allowed input fields are `name`, `email`, `password`, and `active` only.

- `name`: required, non-blank, maximum 100 characters.
- `email`: required, non-blank, valid email syntax, maximum 254 characters; stored trimmed and lower-cased.
- `password`: optional. Omitted or blank means no password change. A supplied non-blank value must satisfy the existing strong-password rule and is BCrypt-hashed before persistence.
- `active`: optional; when present, explicitly activates/deactivates the account.
- Any other request property (including `role`, `id`, `createdAt`, or `lastLogin`) causes a 400 validation response. Role updates are therefore not supported by this endpoint and cannot be used for escalation.

## Authorization and API compatibility

`/api/users/**` remains ADMIN-only in `SecurityConfig`, and `UserController` retains `@PreAuthorize("hasRole('ADMIN')")`. Unauthenticated/unauthorized callers cannot update users. The endpoint URL, method, and successful response remain unchanged. The existing admin UI was adjusted to use the explicit input contract.

## Tests executed

Command:

```powershell
.\mvnw.cmd test "-Dlogging.level.org.springframework=warn" "-Dlogging.level.org.hibernate.SQL=warn"
```

Measured Surefire result: **122 tests, 0 failures, 0 errors, 0 skipped** across 20 reports.

Focused command:

```powershell
.\mvnw.cmd -Dtest=UserControllerValidationTest test
```

Focused result: **6 tests, 0 failures, 0 errors, 0 skipped**.

The tests use the existing isolated in-memory H2 configuration and `spring.flyway.enabled=false`. No MySQL, original ecom, production database, Flyway operation, migration, or schema file was accessed or changed.

## Remaining risks

- Registration has pre-existing manual validation and duplicate handling separate from this update flow; it was intentionally left outside Phase 12B scope.
- Database uniqueness remains the final authority under concurrency; the update service catches the corresponding persistence conflict and returns the same safe 409 response.
