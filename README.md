# E-Commerce Admin Panel

A full-stack e-commerce admin + customer application with a secure Spring Boot backend,
a React admin/customer UI, and analytics/ML modules (RFM, recommendations, forecasting,
demand prediction, risk triage).

See [`DOCUMENTATION.md`](./DOCUMENTATION.md) for architecture, API reference, data model,
ML methodology and limitations.

## Stack
- **Frontend:** React 19 + Vite + Tailwind CSS + Axios + React Router + Recharts
- **Backend:** Java 17 + Spring Boot 3.4 + Spring Security + JWT + Spring Data JPA
- **Database:** MySQL (H2 for tests)

## Backend setup (`springapp`)

There is **no implicit database configuration**. The base profile has no default
`DB_URL`, so the application fails fast at startup unless `DB_URL`, `DB_USERNAME`
and `DB_PASSWORD` are supplied explicitly — it can never silently connect to (or
mutate) a local `ecom` database. The base profile also never changes the schema
(`ddl-auto=validate`).

Run the test suite (in-memory H2, no MySQL required):
```bash
./mvnw clean test
```

### Local development profile

`dev` is the explicit local-development profile. It lets Hibernate create/update
the schema, so point it at a **disposable** development database — never at a
populated one:
```bash
export SPRING_PROFILES_ACTIVE=dev
export DB_URL="jdbc:mysql://localhost:3306/ecom_dev"
export DB_USERNAME=<local-user>
export DB_PASSWORD=<local-password>
./mvnw spring-boot:run   # API on :8080
```
Optional local overrides: `JWT_SECRET` (a 32+ char secret; a dev-only fallback
is used when unset), `JWT_EXPIRATION_MS`, `APP_CORS_ORIGINS`, `PORT`.

### Production configuration

Start the backend with `SPRING_PROFILES_ACTIVE=prod`. The production profile
does not provide fallback values and requires all of the following runtime
environment variables: `DB_URL` (a `jdbc:mysql:` URL), `DB_USERNAME`,
`DB_PASSWORD`, `JWT_SECRET` (at least 32 UTF-8 bytes), and
`APP_CORS_ORIGINS` (one or more comma-separated explicit HTTPS origins).
Startup rejects blank values, wildcard/null/local origins, HTTP origins, and
malformed origins. Provide these values through the deployment secret manager
or runtime environment; do not put them in Dockerfile instructions or frontend
`VITE_*` variables.

## Frontend setup (`reactapp`)
```bash
npm install
npm run dev      # dev server
npm run lint     # ESLint
npm run build    # production build
```
The API base URL comes from `VITE_API_BASE_URL` (`src/utils/api.js`), falling back to
`http://localhost:8080` when unset. An explicitly empty value makes requests
same-origin, which is how the Docker image is built (nginx proxies `/api` and `/auth`).

## Health endpoint

The backend exposes `GET /actuator/health` (unauthenticated, no details). With
`management.endpoint.health.probes.enabled=true` the liveness and readiness groups
are also available at `/actuator/health/liveness` and `/actuator/health/readiness`.
Only the health endpoint is exposed; every other actuator endpoint is not published.

## Docker (local full-stack demo)

```bash
cp .env.example .env     # then set strong DB passwords and a 32+ char JWT_SECRET
docker compose up --build
# frontend: http://localhost:3000  (nginx serves the SPA and proxies the API)
docker compose down      # add -v to also drop the MySQL volume
```

`docker-compose.yml` runs MySQL with a named volume, waits for the database health
check before starting the backend, and waits for the backend health check before
starting the frontend. The backend runs the `docker` Spring profile, which is a
**demo profile only**: it lets Hibernate create the schema on an empty volume.
MySQL is not published to the host and no credentials are embedded in the images.

Production must run the backend with `SPRING_PROFILES_ACTIVE=prod`, where Hibernate
is `validate`-only, Flyway is disabled, and configuration is supplied entirely
through the environment.

## Roles
- **ADMIN** – manage products, orders, users, coupons, and view analytics/ML insights.
- **USER** – browse products, view recommendations, place/cancel own orders, view profile.

Customer accounts are created via registration. Admin accounts can only be created by an
existing admin (`POST /auth/register/admin`).

## Tests

```bash
cd springapp && ./mvnw test     # H2 only; no MySQL required
cd reactapp  && npm run test:run && npm run lint && npm run build
```

GitHub Actions (`.github/workflows/quality.yml`) runs both suites, the ESLint pass and
the production Vite build on every push and pull request. It performs no deployment
and never touches a database or migration.
 
