# Patient Management System

A microservices-based healthcare patient management system built with Spring Boot: an API gateway, JWT authentication, gRPC between services, event-driven messaging with Kafka and Protobuf, PostgreSQL with Flyway migrations, all running locally with Docker Compose.

> 📍 **Roadmap & learning plan:** [`docs/implementation-plans/roadmap.md`](docs/implementation-plans/roadmap.md) — audit findings and the phased plan (testing, CI, Auth0, reliability, observability, Kubernetes, AWS). Each phase has its own plan file in the same folder.

## 📋 Table of Contents

- [Architecture](#-architecture)
- [Services](#-services)
- [Technology Stack](#-technology-stack)
- [Getting Started](#-getting-started)
- [API Endpoints](#-api-endpoints)
- [Errors](#-errors)
- [Service Communication](#-service-communication)
- [Database & Migrations](#-database--migrations)
- [Health Checks](#-health-checks)
- [Testing](#-testing)
- [Configuration](#-environment-variables-and-configuration)
- [Project Structure](#-project-structure)

## 🏗️ Architecture

```
Client ─► api-gateway :4004 (Spring Cloud Gateway, reactive)
            │  /auth/**          → auth-service :4005 ──► AuthDB (Postgres, host port 5001)
            │  /api/patients/**  → JwtValidation filter ──GET /validate──► auth-service
            │                     → patient-service :4000 ──► PatientDB (Postgres, host port 5000)
            │                            ├─ gRPC (sync, 3s deadline) ─► billing-service :9001
            │                            └─ Kafka topic "patient" (Protobuf, after DB commit) ─► analytics-service :4003
```

Only the gateway (4004) is the intended entry point. patient-service and auth-service are not published to the host.

> The former AWS CDK / LocalStack setup was removed on purpose. Cloud deployment comes back later with Terraform (roadmap phase 8).

## 🔧 Services

| Service | Port | Responsibility |
|---|---|---|
| **api-gateway** | 4004 | Routing; validates the token via auth-service for `/api/patients/**` (401 invalid token, 503 auth-service unavailable) |
| **auth-service** | 4005 | Login, issues HS256 JWTs, `GET /validate` |
| **patient-service** | 4000 | Patient CRUD, gRPC client of billing, Kafka producer |
| **billing-service** | 4002 (HTTP), 9001 (gRPC) | gRPC server `CreateBillingAccount` (currently a stub) |
| **analytics-service** | 4003 | Kafka consumer of patient events (currently logs them) |

## 🛠️ Technology Stack

| Category | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot **4.0.9** (all services) |
| API Gateway | Spring Cloud Gateway 5 (Spring Cloud 2025.1, WebFlux) |
| Security | Spring Security, jjwt 0.12.6 (Auth0 planned — roadmap phase 3) |
| Database | PostgreSQL 16, Spring Data JPA, **Flyway** migrations |
| Messaging | Apache Kafka 4.3.2 (KRaft), Protobuf payloads |
| RPC | gRPC + Protobuf (contracts in root `proto/`) |
| Errors | RFC 9457 Problem Details |
| Ops | Spring Boot Actuator health probes |
| Build / Run | Maven, Docker, Docker Compose |
| Testing | JUnit, REST Assured |

## 🚀 Getting Started

**Prerequisites:** Docker Desktop (with Compose). A local JDK/Maven is optional — every image is built from source inside Docker.

1. Create a root `.env` file (see [Configuration](#-environment-variables-and-configuration)).
2. Start everything:
   ```bash
   docker compose up --build
   ```
3. Wait until all containers are **healthy**:
   ```bash
   docker compose ps
   ```
   Services start in dependency order (`depends_on: service_healthy`): databases and Kafka → billing/auth → patient → gateway.

Useful commands:

```bash
docker compose up --build patient-service   # rebuild and restart one service
docker compose logs -f patient-service      # follow logs
docker compose down                         # stop (keeps database data)
docker compose down -v                      # stop AND delete database volumes
```

## 📡 API Endpoints

All requests go through the gateway at `http://localhost:4004`. Ready-made requests are in [`api-requests/`](api-requests) (IntelliJ HTTP client).

### Authentication

```http
POST /auth/login
Content-Type: application/json

{ "email": "testuser@test.com", "password": "password123" }
```
→ `200 {"token": "..."}`. The test user exists **only in local development** (see [Database & Migrations](#-database--migrations)).

```http
GET /auth/validate
Authorization: Bearer <token>
```

### Patients (require `Authorization: Bearer <token>`)

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/patients?page=0&size=20&sort=name,asc` | Paginated; defaults page 0, size 20, sort by name; max size 100 |
| `POST` | `/api/patients` | `201`; also creates a billing account (gRPC) and publishes a Kafka event |
| `PUT` | `/api/patients/{id}` | `200`; `registrationDate` not required |
| `DELETE` | `/api/patients/{id}` | `204` |

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
- `GET /api-docs/patients`
- `GET /api-docs/auth`

### Billing (gRPC, direct)
```http
GRPC localhost:9001/BillingService/CreateBillingAccount

{ "patientId": "12345", "name": "John Doe", "email": "john@example.com" }
```

## ❗ Errors

Errors use **RFC 9457 Problem Details** (`application/problem+json`):

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "A patient with this email already exists", "instance": "/patients" }
```

| Status | When |
|---|---|
| 400 | Validation failed (with an `errors` map per field), malformed JSON / invalid date, unknown sort field, invalid UUID |
| 401 | Missing, invalid or expired token |
| 404 | Patient not found |
| 409 | Email already used (also under concurrent requests) |
| 503 | billing-service or auth-service unavailable / too slow — nothing was saved, retry later |

## 🔄 Service Communication

- **REST:** client → gateway → auth-service / patient-service.
- **gRPC:** patient-service → billing-service with a **deadline** (`BILLING_SERVICE_DEADLINE`, default `3s`). Patient creation is one DB transaction: if billing fails, the patient insert is rolled back and the API answers 503.
- **Kafka:** after the DB transaction **commits**, patient-service publishes `PatientEvent` (Protobuf) to topic `patient`, keyed by patient id (per-patient ordering). analytics-service consumes it (group `analytics-service`).
- **Contracts:** one copy of each `.proto` in [`proto/`](proto). Services compile them via `<protoSourceRoot>../proto</protoSourceRoot>`, which is why the patient/billing/analytics images are built with the repo root as Docker build context.

Logs contain **IDs only** — no patient names or emails.

## 🗃️ Database & Migrations

| DB | Container | Host port | Owner |
|---|---|---|---|
| PatientDB | `patient-postgres` | 5000 | patient-service |
| AuthDB | `auth-postgres` | 5001 | auth-service |

The schema is managed by **Flyway** (`src/main/resources/db/migration/V*.sql`); Hibernate only validates it (`ddl-auto: validate`).

- To change the schema, **add** a new migration (`V2__...sql`) — never edit one that already ran.
- Existing databases created by older versions are baselined automatically (`baseline-on-migrate`).
- The test user `testuser@test.com` lives in `auth-service/src/main/resources/db/dev-data/` and is loaded **only** because `docker-compose.yml` sets `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev-data`. Production must not set it.

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

End-to-end tests (REST Assured) run against the running stack on `localhost:4004`:

```bash
docker compose up -d --build
cd Integration-tests && mvn test
# without a local Maven:
docker run --rm --network host -v "$PWD/Integration-tests":/ws -v pm-m2-cache:/root/.m2 -w /ws \
  maven:3.9.9-eclipse-temurin-21 mvn -B test
```

Unit/integration tests per service (Testcontainers) are planned in roadmap phase 1.

## 🔐 Environment Variables and Configuration

Each service's `application.yml` holds non-secret defaults (`${ENV:default}`). Secrets come only from a root `.env` (ignored by Git), which `docker-compose.yml` requires (`${VAR:?}` — Compose fails fast if one is missing):

```dotenv
PATIENT_DB_NAME=your_patient_database
PATIENT_DB_USER=your_patient_database_user
PATIENT_DB_PASSWORD=your_patient_database_password

AUTH_DB_NAME=your_auth_database
AUTH_DB_USER=your_auth_database_user
AUTH_DB_PASSWORD=your_auth_database_password

JWT_SECRET=your_base64_encoded_secret_of_at_least_32_bytes
```

Non-secret values set by Compose:

| Variable | Service | Value |
|---|---|---|
| `BILLING_SERVICE_ADDRESS` / `_PORT` | patient | `billing-service` / `9001` |
| `BILLING_SERVICE_DEADLINE` | patient | `3s` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | patient, analytics | `kafka:9092` |
| `SPRING_FLYWAY_LOCATIONS` | auth | includes `db/dev-data` (local only) |
| `AUTH_SERVICE_URL` / `PATIENT_SERVICE_URL` | gateway | `http://auth-service:4005` / `http://patient-service:4000` |
| `AUTH_SERVICE_TIMEOUT` | gateway | default `3s` |

## 📁 Project Structure

```
Patient-Management/
├── api-gateway/          # Spring Cloud Gateway (WebFlux)
├── auth-service/         # Login + JWT (Flyway: db/migration, db/dev-data)
├── patient-service/      # Patient CRUD, gRPC client, Kafka producer (Flyway)
├── billing-service/      # gRPC server
├── analytics-service/    # Kafka consumer
├── proto/                # Shared gRPC + Kafka Protobuf contracts
├── Integration-tests/    # REST Assured end-to-end tests
├── api-requests/         # IntelliJ .http requests
├── grpc-requests/        # IntelliJ gRPC requests
├── docs/implementation-plans/  # Roadmap and per-phase plans
└── docker-compose.yml
```

## 📝 License

This project is licensed under the MIT License.
