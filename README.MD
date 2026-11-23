# Patient Management System

[![CI](https://github.com/mohamadalabsi/Patient-Management/actions/workflows/ci.yml/badge.svg?branch=dev-branch)](https://github.com/mohamadalabsi/Patient-Management/actions/workflows/ci.yml)

A microservices-based healthcare patient management system built with Spring Boot: an API gateway, OAuth2 / OIDC security with Auth0 (every service verifies the tokens itself), gRPC between services, event-driven messaging with Kafka and Protobuf, PostgreSQL with Flyway migrations, all running locally with Docker Compose.

> 📍 **Roadmap & learning plan:** [`docs/implementation-plans/roadmap.md`](docs/implementation-plans/roadmap.md) — audit findings and the phased plan (testing, CI, Auth0, reliability, observability, Kubernetes, AWS). Each phase has its own plan file in the same folder.

## 📋 Table of Contents

- [Architecture](#-architecture)
- [Services](#-services)
- [Technology Stack](#-technology-stack)
- [Getting Started](#-getting-started)
- [API Endpoints](#-api-endpoints)
- [Security](#-security)
- [Errors](#-errors)
- [Service Communication](#-service-communication)
- [Database & Migrations](#-database--migrations)
- [Health Checks](#-health-checks)
- [Testing](#-testing)
- [CI/CD](#-cicd)
- [Configuration](#-environment-variables-and-configuration)
- [Project Structure](#-project-structure)

## 🏗️ Architecture

```
                 Auth0 (login, users, permissions; signs tokens)
                   │ access token                 ▲ client credentials (patient-service's own token)
                   ▼                              │
Client ─token─► api-gateway :4004 (Spring Cloud Gateway, reactive) - verifies the token (Auth0 public keys)
                  │  /api/patients/** → patient-service :4000 - verifies again + permission per endpoint
                  │                       ├──► PatientDB (Postgres, host port 5000)
                  │                       ├─ gRPC + service token (3s deadline) ─► billing-service :9001
                  │                       │                                      (checks create:billing-accounts)
                  │                       └─ Kafka topic "patient" (Protobuf, after DB commit) ─► analytics-service :4003
```

Only the gateway (4004) is the intended entry point; patient-service is not published to the host.
**auth-service is retired** (Phase 3): its code stays in the repo for reference, but it no longer runs -
login and tokens are Auth0's job.

> The former AWS CDK / LocalStack setup was removed on purpose. Cloud deployment comes back later with Terraform (roadmap phase 8).

## 🔧 Services

| Service | Port | Responsibility |
|---|---|---|
| **api-gateway** | 4004 | Routing, CORS; verifies the Auth0 token for `/api/**` (401 if missing/invalid) |
| **patient-service** | 4000 | Patient CRUD with per-endpoint permissions, gRPC client of billing (own service token), Kafka producer |
| **billing-service** | 4002 (HTTP), 9001 (gRPC) | gRPC server `CreateBillingAccount` (currently a stub); requires `create:billing-accounts` |
| ~~auth-service~~ | – | **retired** (Phase 3): was login + HS256 JWTs + `/validate`; replaced by Auth0 |
| **analytics-service** | 4003 | Kafka consumer of patient events (currently logs them) |

## 🛠️ Technology Stack

| Category | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot **4.0.9** (all services) |
| API Gateway | Spring Cloud Gateway 5 (Spring Cloud 2025.1, WebFlux) |
| Security | **Auth0** (OAuth2 / OIDC), Spring Security 7 resource servers + OAuth2 client (client credentials), gRPC call credentials |
| Database | PostgreSQL 16, Spring Data JPA, **Flyway** migrations |
| Messaging | Apache Kafka 4.3.2 (KRaft), Protobuf payloads |
| RPC | gRPC + Protobuf (contracts in root `proto/`) |
| Errors | RFC 9457 Problem Details |
| Ops | Spring Boot Actuator health probes |
| Build / Run | Maven, Docker, Docker Compose |
| CI/CD | GitHub Actions, JaCoCo, Trivy, Docker Hub, Dependabot |
| Testing | JUnit 5, Mockito, AssertJ, Spring test slices, **Testcontainers** (Postgres, Kafka), Awaitility, in-process gRPC, REST Assured |

## 🚀 Getting Started

**Prerequisites:** Docker Desktop (with Compose). A local JDK/Maven is optional — every image is built from source inside Docker.

1. Set up Auth0 once (free account): an API with identifier `https://api.patient-management.local`,
   RBAC + "permissions in the access token" on, and two Machine-to-Machine apps - see
   [Security](#-security) and the step-by-step list in
   [phase-03-security-auth0.md → "What you need to do"](docs/implementation-plans/phase-03-security-auth0.md).
2. Create a root `.env` file (see [Configuration](#-environment-variables-and-configuration)).
3. Start everything:
   ```bash
   docker compose up --build
   ```
4. Wait until all containers are **healthy**:
   ```bash
   docker compose ps
   ```
   Services start in dependency order (`depends_on: service_healthy`): databases and Kafka → billing → patient → gateway.

Useful commands:

```bash
docker compose up --build patient-service   # rebuild and restart one service
docker compose logs -f patient-service      # follow logs
docker compose down                         # stop (keeps database data)
docker compose down -v                      # stop AND delete database volumes
```

## 📡 API Endpoints

All requests go through the gateway at `http://localhost:4004`. Ready-made requests are in [`api-requests/`](api-requests) (IntelliJ HTTP client).

### Getting a token

Tokens come from **Auth0** (there is no `/auth/login` any more). For testing, use the token of your
Auth0 test application:

- Auth0 dashboard → APIs → Patient Management API → **Test** tab → copy `access_token`, or
- [`api-requests/auth0/get-token.http`](api-requests/auth0/get-token.http) (client credentials; stores it in `{{token}}`), or
- Postman: Authorization → OAuth 2.0 → Client Credentials, token URL `https://<tenant>/oauth/token`,
  extra token-request parameter `audience=https://api.patient-management.local`.

### Patients (require `Authorization: Bearer <token>` with the listed permission)

| Method | Path | Permission | Notes |
|---|---|---|---|
| `GET` | `/api/patients?page=0&size=20&sort=name,asc` | `read:patients` | Paginated; defaults page 0, size 20, sort by name; max size 100 |
| `POST` | `/api/patients` | `write:patients` | `201`; also creates a billing account (gRPC) and publishes a Kafka event |
| `PUT` | `/api/patients/{id}` | `write:patients` | `200`; `registrationDate` not required |
| `DELETE` | `/api/patients/{id}` | `delete:patients` | `204` |

Create body (dates are `yyyy-MM-dd`; `birthDate` must be in the past):
```json
{
  "name": "John Doe",
  "email": "john.doe@example.com",
  "address": "123 Main St, Frankfurt, Germany",
  "birthDate": "1990-01-15",
  "registrationDate": "2025-01-01"
}
```

Paginated response:
```json
{
  "content": [ { "id": "…", "name": "John Doe", "email": "…", "address": "…", "birthDate": "1990-01-15" } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

### API docs (OpenAPI)
- `GET /api-docs/patients` (public)

### Billing (gRPC, direct)
```http
GRPC localhost:9001/BillingService/CreateBillingAccount
authorization: Bearer <token with create:billing-accounts>

{ "patientId": "12345", "name": "John Doe", "email": "john@example.com" }
```
Only patient-service has that permission (its own Auth0 app). Without a token → `UNAUTHENTICATED`,
with a token lacking the permission → `PERMISSION_DENIED`. Health and server reflection stay public.

## 🔐 Security

Details, diagrams and the reasoning: [phase-03-security-auth0.md](docs/implementation-plans/phase-03-security-auth0.md).

- **Auth0 is the identity provider.** It logs people in, stores users, and signs **access tokens** (JWT, RS256)
  with its private key. Our services never see a password.
- **Every service verifies tokens itself** with Auth0's public keys (JWKS, fetched once and cached):
  signature, issuer (`AUTH0_ISSUER_URI`), audience (`AUTH0_AUDIENCE`), expiry. No call per request.
- **Two checks for the patient API** (defence in depth): the gateway (valid token for `/api/**`), then
  patient-service (valid token + the endpoint's permission via `@PreAuthorize`).
- **Permissions** (`read:patients`, `write:patients`, `delete:patients`, `create:billing-accounts`) are defined
  on the Auth0 API and given to people (roles) or programs (Machine-to-Machine apps). The token's
  `permissions` claim carries them; the code only *requires* them.
- **Service-to-service:** patient-service has its own Auth0 app (client credentials, only
  `create:billing-accounts`), gets a token (cached until shortly before it expires) and sends it on every
  gRPC call; billing-service checks it in a gRPC interceptor.
- **401 vs 403:** 401 = no valid token (who are you?), 403 = valid token, missing permission (you may not).
- **CORS:** only the gateway answers browsers; allowed origins via `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000`).
- **Tests and CI never use the real Auth0:** unit tests sign their own tokens or use a test decoder;
  the E2E stack adds a local fake issuer (`docker-compose.e2e.yml`, mock-oauth2-server).

## ❗ Errors

Errors use **RFC 9457 Problem Details** (`application/problem+json`):

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "A patient with this email already exists", "instance": "/patients" }
```

| Status | When |
|---|---|
| 400 | Validation failed (with an `errors` map per field), malformed JSON / invalid date, unknown sort field, invalid UUID |
| 401 | Missing, invalid or expired token, or a token for another issuer/audience (`WWW-Authenticate: Bearer`) |
| 403 | Valid token, but the permission for this operation is missing |
| 404 | Patient not found |
| 409 | Email already used (also under concurrent requests) |
| 503 | billing-service unavailable / too slow / refused the service token — nothing was saved, retry later |

## 🔄 Service Communication

- **REST:** client → gateway → patient-service (the client's token is forwarded unchanged).
- **gRPC:** patient-service → billing-service with patient-service's own **service token** and a **deadline** (`BILLING_SERVICE_DEADLINE`, default `3s`). Patient creation is one DB transaction: if billing fails, the patient insert is rolled back and the API answers 503.
- **Kafka:** after the DB transaction **commits**, patient-service publishes `PatientEvent` (Protobuf) to topic `patient`, keyed by patient id (per-patient ordering). analytics-service consumes it (group `analytics-service`).
- **Contracts:** one copy of each `.proto` in [`proto/`](proto). Services compile them via `<protoSourceRoot>../proto</protoSourceRoot>`, which is why the patient/billing/analytics images are built with the repo root as Docker build context.

Logs contain **IDs only** — no patient names or emails.

## 🗃️ Database & Migrations

| DB | Container | Host port | Owner |
|---|---|---|---|
| PatientDB | `patient-postgres` | 5000 | patient-service |
| ~~AuthDB~~ | – | – | retired with auth-service (Phase 3); an old local volume is not deleted automatically |

The schema is managed by **Flyway** (`src/main/resources/db/migration/V*.sql`); Hibernate only validates it (`ddl-auto: validate`).

- To change the schema, **add** a new migration (`V2__...sql`) — never edit one that already ran.
- Existing databases created by older versions are baselined automatically (`baseline-on-migrate`).
- *(Retired with auth-service)* the dev test user `testuser@test.com` was seeded from `auth-service/src/main/resources/db/dev-data/`. Users now live in Auth0.

Database credentials come from `.env` and are applied when a volume is first created. After changing them run `docker compose down -v` (deletes local data).

## ❤️ Health Checks

Every service exposes Actuator (only `health` and `info`):

```
/actuator/health            {"status":"UP"}
/actuator/health/liveness   process alive?
/actuator/health/readiness  ready for traffic? (used by docker compose healthchecks)
```

The gateway's is reachable at `http://localhost:4004/actuator/health`.

## 🧪 Testing

Two kinds of test suites (details: [phase-01-testing.md](docs/implementation-plans/phase-01-testing.md)):

**1. Per-service tests** (`<service>/src/test`) - unit, web/repository slices, and integration tests
against **real Postgres/Kafka started by Testcontainers**. They need **only Docker** - no compose
stack, no `.env`, no fixed ports (they also run while the stack is up).

```bash
scripts/mvn-test.sh patient-service                                   # one service, all tests
scripts/mvn-test.sh patient-service -Dtest=PatientControllerTest      # one class
scripts/mvn-test.sh patient-service -Dtest='PatientServiceTest*#create*'
# with a local JDK instead:  cd patient-service && ./mvnw test
```

The script runs `mvn clean test` in the `maven:3.9.9-eclipse-temurin-21` image with the Docker socket
mounted, so Testcontainers can start sibling containers.

| Service | Tests | Runtime* | What is covered |
|---|---|---|---|
| patient-service | 51 | ~90 s | service rules (Mockito), HTTP contract (`@WebMvcTest`), **permissions per endpoint (401/403)**, repository on real Postgres (`@DataJpaTest`), creation flow: rollback + after-commit Kafka event + gRPC deadline + **service token sent and cached** (fake billing + fake token endpoint) |
| auth-service | 23 | ~45 s | *(retired service, still tested)* JWT, login rules, `/login` + `/validate` contract |
| billing-service | 6 | ~25 s | gRPC API over in-process transport; **token check: none / invalid → UNAUTHENTICATED, wrong permission → PERMISSION_DENIED** |
| analytics-service | 3 | ~25 s | Kafka consumer on real Kafka, malformed message skipped, no PII logged |
| api-gateway | 12 (+8 old, skipped) | ~27 s | **token checks with self-signed test tokens** (valid, missing, wrong audience/issuer, expired, forged), routing, CORS |

\* on a laptop with Docker Desktop, images cached; includes Maven startup and `clean`.

The Dockerfiles still build with `-DskipTests`: running Testcontainers inside `docker build`
would need Docker-in-Docker. Tests run as their own step - locally with the script, in CI as the
`test` job (see [CI/CD](#-cicd)).

**2. End-to-end tests** (`Integration-tests/`, REST Assured) call the running stack through the gateway.
They get their tokens from a **local fake issuer**, so the stack is started with the E2E override file
(it adds `mock-oauth2-server` and points the services at it - your `.env` still needs the variables):

```bash
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build --wait
docker run --rm -v "$PWD/Integration-tests":/ws -v pm-m2-cache:/root/.m2 -w /ws \
  --add-host=host.docker.internal:host-gateway maven:3.9.9-eclipse-temurin-21 \
  mvn -B test -Dgateway.url=http://host.docker.internal:4004 \
              -Dtoken.url=http://host.docker.internal:8090/default/token
# with a local JDK:  cd Integration-tests && mvn test   (defaults: localhost:4004, localhost:8090)
```

## ⚙️ CI/CD

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) checks every pull request into
`dev-branch` / `master` and publishes images after a merge. Plain-language explanation and all details:
[phase-02-ci-docker-hub.md](docs/implementation-plans/phase-02-ci-docker-hub.md).

```
changes ─► test (one job per changed service) ─► e2e (whole stack) ─► images (one per service) ─► ci-result
```

| Job | What it does | Pull request | After a merge |
|---|---|---|---|
| `changes` | which services changed (`dorny/paths-filter`; `proto/` counts for patient, billing, analytics) | ✓ | ✓ |
| `test` | `./mvnw -B verify` per changed service: all tests + JaCoCo coverage (summary on the run page, reports as artifacts) | ✓ | ✓ |
| `e2e` | `docker compose up --build --wait` with a generated throwaway env file, then `Integration-tests`; container logs as artifact on failure | ✓ | ✓ |
| `images` | build the image, Trivy scan (fails on fixable CRITICAL), **push** to Docker Hub | build + scan | build + scan + **push** |
| `ci-result` | one summary check (red if any job failed) - the check to require in branch protection | ✓ | ✓ |

**Images** are published as `docker.io/<dockerhub-user>/pm-<service>` with the tags:

| Tag | Meaning |
|---|---|
| `sha-<7-char commit>` | exactly that commit - immutable, use it for deployments |
| `dev` | newest build of `dev-branch` (moves) |
| `latest` | newest build of `master` (moves) |

```bash
docker pull <dockerhub-user>/pm-patient-service:dev
```

The Dockerfiles split the Spring Boot jar into layers (libraries / our code), so a code change only
uploads a small layer. **Dependabot** ([`.github/dependabot.yml`](.github/dependabot.yml)) opens weekly
PRs into `dev-branch` for Maven dependencies, base images, compose images and GitHub Actions.

Needed once (repository settings): the secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` (a Docker Hub
access token with Read & Write scope). The CI database password is generated per run; CI uses the fake token issuer, so **no Auth0 secrets are stored in GitHub**.

To check workflow files before pushing:

```bash
docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12
```

## 🔐 Environment Variables and Configuration

Each service's `application.yml` holds non-secret defaults (`${ENV:default}`). Secrets come only from a root `.env` (ignored by Git), which `docker-compose.yml` requires (`${VAR:?}` — Compose fails fast if one is missing):

```dotenv
PATIENT_DB_NAME=your_patient_database
PATIENT_DB_USER=your_patient_database_user
PATIENT_DB_PASSWORD=your_patient_database_password

# Auth0 (Phase 3): tenant URL WITH the trailing "/", and the API identifier
AUTH0_ISSUER_URI=https://<your-tenant>.eu.auth0.com/
AUTH0_AUDIENCE=https://api.patient-management.local

# Auth0 Machine-to-Machine app "patient-service" (only create:billing-accounts)
PATIENT_SERVICE_CLIENT_ID=...
PATIENT_SERVICE_CLIENT_SECRET=...

# no longer used since Phase 3 (auth-service retired) - safe to delete:
# AUTH_DB_NAME / AUTH_DB_USER / AUTH_DB_PASSWORD / JWT_SECRET
```

Non-secret values set by Compose:

| Variable | Service | Value |
|---|---|---|
| `BILLING_SERVICE_ADDRESS` / `_PORT` | patient | `billing-service` / `9001` |
| `BILLING_SERVICE_DEADLINE` | patient | `3s` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | patient, analytics | `kafka:9092` |
| `PATIENT_SERVICE_URL` | gateway | `http://patient-service:4000` |
| `CORS_ALLOWED_ORIGINS` | gateway | default `http://localhost:3000` (comma-separated) |
| `AUTH0_ISSUER_URI` / `AUTH0_AUDIENCE` | gateway, patient, billing | from `.env` (E2E: the fake issuer) |
| `AUTH0_TOKEN_URI` | patient | default `<AUTH0_ISSUER_URI>oauth/token` (E2E: the fake issuer) |

## 📁 Project Structure

```
Patient-Management/
├── .github/              # CI workflow (workflows/ci.yml) + Dependabot config
├── api-gateway/          # Spring Cloud Gateway (WebFlux)
├── auth-service/         # RETIRED in Phase 3 (Auth0 replaces it) - kept for reference, not in compose
├── patient-service/      # Patient CRUD, gRPC client, Kafka producer (Flyway)
├── billing-service/      # gRPC server
├── analytics-service/    # Kafka consumer
├── proto/                # Shared gRPC + Kafka Protobuf contracts
├── Integration-tests/    # REST Assured end-to-end tests
├── scripts/mvn-test.sh   # run one service's tests in Docker (no local JDK)
├── .claude/              # Claude Code settings + project skills (one per roadmap phase)
├── api-requests/         # IntelliJ .http requests (auth0/get-token.http first)
├── grpc-requests/        # IntelliJ gRPC requests
├── docs/implementation-plans/  # Roadmap and per-phase plans
├── docker-compose.yml
└── docker-compose.e2e.yml  # E2E/CI override: fake token issuer (mock-oauth2-server)
```

## 📝 License

This project is licensed under the MIT License.
