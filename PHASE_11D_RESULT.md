# Phase 11D Result — Flyway Implementation (Restricted Test Database Only)

## Scope and safety

Flyway support is implemented only for the explicit `flyway-test` profile. No
MySQL connection, Flyway migration, DDL, DML, or access to the original `ecom`
database was performed during this phase.

The base, production, and H2-test configurations explicitly disable Flyway.
The isolated profile has no URL, username, or password fallback values and uses
only `FLYWAY_TEST_DB_URL`, `FLYWAY_TEST_DB_USERNAME`, and
`FLYWAY_TEST_DB_PASSWORD`.

## Files changed

- `springapp/pom.xml`
- `springapp/src/main/resources/application.properties`
- `springapp/src/main/resources/application-prod.properties`
- `springapp/src/test/resources/application.properties`
- `springapp/src/main/resources/application-flyway-test.properties`
- `springapp/src/main/resources/META-INF/spring.factories`
- `springapp/src/main/java/com/examly/springapp/config/FlywayTestSafetyGuard.java`
- `springapp/src/main/resources/db/migration/V1__baseline_schema.sql`
- `springapp/src/test/java/com/examly/springapp/config/FlywayTestSafetyGuardTest.java`

## Dependencies

Added Spring Boot-managed dependencies with no explicit versions:

- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-mysql`

The Spring Boot 3.4.0 dependency management metadata resolves Flyway 10.20.1.
No Spring Boot, Java, or unrelated dependency version was changed.

## Configuration and safety guard

`spring.flyway.enabled=false` is set in base, production, and H2-test
configuration. `application-flyway-test.properties` enables Flyway, selects
`classpath:db/migration`, keeps `baseline-on-migrate=false`, and uses Hibernate
`validate`.

`FlywayTestSafetyGuard` is registered as an `EnvironmentPostProcessor`, which
runs before application-context and Flyway bean creation. With `flyway-test`
active it rejects:

- a combined `prod` profile;
- disabled Flyway;
- a missing or non-MySQL datasource URL;
- a URL other than `jdbc:mysql://127.0.0.1:3306/ecom_flyway_test`;
- any username other than `flyway_test`.

The guard never prints datasource values or credentials. The restricted database
account remains an operational prerequisite; configuration guards complement,
but do not replace, MySQL grants scoped only to `ecom_flyway_test`.

## V1 baseline schema

`V1__baseline_schema.sql` was derived from `Dump20261031.sql` and creates only
the approved seven tables: `audit_logs`, `coupons`, `orders`, `payments`,
`product`, `users`, and `order_item`.

It preserves the approved MySQL definitions, including InnoDB, utf8mb4,
`utf8mb4_0900_ai_ci`, existing auto-increment attributes, primary keys, unique
keys, `users.role ENUM('ADMIN','USER')`, DOUBLE fields, nullable
`orders.user_id`, and only the two existing `order_item` foreign keys. It does
not add the six deferred indexes, scalar-reference foreign keys, production
data, or destructive/data-manipulation statements.

## Verification

| Check | Result |
| --- | --- |
| Focused safety/profile/configuration tests | Passed: 18 tests, 0 failures, 0 errors, 0 skipped |
| Complete backend suite (`mvn test`) | Passed: 111 tests across 18 reports, 0 failures, 0 errors, 0 skipped |
| Maven compilation | Passed as part of both Maven test runs |
| Real MySQL migration | Not run |

The registered-guard test started an intentionally invalid `prod,flyway-test`
combination and confirmed it stopped at environment preparation, before context
or Flyway initialization. Existing tests used only their established H2 setup.

## Pending operator steps

Before any real migration, manually confirm that the `flyway_test` account has
access only to `ecom_flyway_test`, provide the three `FLYWAY_TEST_DB_*`
environment variables through an approved secret channel, and obtain explicit
approval to run the isolated profile against an empty test database. The
original `ecom` database must not be supplied to this profile or account.
