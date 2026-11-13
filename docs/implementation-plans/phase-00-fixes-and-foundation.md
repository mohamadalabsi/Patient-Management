# Phase 0 — Fixes & Foundation

> Status: **planned — awaiting approval**
> Branch: *(created by the owner before implementation starts)*
> Base: `dev-branch` @ `d669b5d`
> Findings referenced (B#, S#, A#) are defined in [roadmap.md](roadmap.md#4-audit-findings).

## Goal

Make the existing system **correct, consistent and observable enough to build on**, without adding new features or new infrastructure:

- every service on the same, supported Spring Boot line;
- the database schema owned by versioned migrations instead of Hibernate;
- one source of truth for gRPC/Kafka contracts;
- correct HTTP status codes and validation;
- the patient-creation flow no longer leaves half-created data behind;
- no patient PII in logs;
- health endpoints so Docker (and later Kubernetes) knows when a service is really ready.

### Out of scope (and where it goes instead)

| Item | Moved to | Why |
|---|---|---|
| B7 — `contextLoads` tests can't run without a DB | Phase 1 | It is a testing problem; Testcontainers is the proper fix |
| Migrating billing from `net.devh` gRPC starter to Spring gRPC | Phase 4 | Works today on Boot 4; a migration is its own risk; Phase 4 deals with gRPC resilience anyway |
| Security (S1–S6, S8, S9) | Phase 3 | Auth0 replaces the current token model |
| Real fix for dual-write (outbox) | Phase 4 | Phase 0 applies an interim fix only |
| Update/delete events (A5) | Phase 4 | Event design belongs with reliability work |

## Target versions (verified on Maven Central, 2026-11-13)

| Component | Now | Target | Reason |
|---|---|---|---|
| Spring Boot (all services) | 3.4.1 (gateway), 4.0.1–4.0.2 (others) | **4.0.9** | Latest patch of the 4.0 line. Boot 4.1 is out, but its Spring Cloud release train (2026.0) is still RC — the gateway needs a GA train |
| Spring Cloud (gateway) | 2024.0.0 | **2025.1.4** | The release train built for Boot 4.0 (brings Spring Cloud Gateway 5.0.4) |
| Gateway starter | `spring-cloud-starter-gateway` | **`spring-cloud-starter-gateway-server-webflux`** | The old artifact no longer exists in Gateway 5.x |
| Gateway route properties | `spring.cloud.gateway.routes` | **`spring.cloud.gateway.server.webflux.routes`** | Renamed together with the artifact |
| Kafka client | `spring-kafka:3.3.0` (hard pin) | **`spring-boot-starter-kafka`** (Boot-managed, spring-kafka 4.0.8) | The pin is a Boot-3-era version; let the Boot BOM choose |
| springdoc | 2.6.0 / 2.8.4 | **3.0.3** | springdoc 3.0.x targets Boot 4.0 (2.x targets Boot 3) |
| Flyway | – | **`spring-boot-starter-flyway`** + `flyway-database-postgresql` (Boot-managed, 11.14.1) | In Boot 4 Flyway auto-configuration lives in its own starter |
| Kafka image | `apache/kafka:latest` | **`apache/kafka:4.3.2`** | Reproducible builds — `latest` changes under you |

## Concepts you will learn in this phase

1. **BOMs and release trains** — why we stop hard-coding library versions and let `spring-boot-starter-parent` / `spring-cloud-dependencies` choose compatible ones.
2. **Database migrations with Flyway** — versioned SQL files (`V1__...sql`), the `flyway_schema_history` table, *baselining* an existing database, and why `ddl-auto` moves from `update` to `validate`.
3. **Environment-specific data** — seed/test users only in development, never in production.
4. **Health checks** — liveness vs readiness, Spring Boot Actuator, Docker `healthcheck` + `depends_on: service_healthy`.
5. **HTTP semantics & RFC 9457 Problem Details** — 400 vs 404 vs 409 vs 503, and Spring's built-in `ProblemDetail` error format.
6. **Deadlines in RPC** — why every remote call needs a timeout.
7. **Transactions and the dual-write problem** — why "save to DB, then call another system" can leave inconsistent data, and how `@TransactionalEventListener(AFTER_COMMIT)` narrows the gap (preparing for the outbox in Phase 4).
8. **Kafka message keys** — ordering is only guaranteed per partition, and the key picks the partition.
9. **PII in logs** — logs get copied to many systems; log IDs, not names/emails.

## Steps

Each step is small, compiles on its own and gets its own commit. I explain each step while doing it; at the ⏸ **checkpoints** I stop so you can run and test the stack yourself.

### 0.1 Repository hygiene
- `.gitignore`: add `target/`, `.idea/`, `*.iml`, OS files.
- Untrack already-committed build output and IDE files (`git rm --cached`; files stay on disk): `Integration-tests/target/**`, `.idea/**`, `Patient-Management.iml`.
- Delete the IntelliJ template `Integration-tests/src/main/java/com/pm/Main.java` (dead code).
- Delete the stray `analytics-service/src/main/kafka_service.proto` (A3; the real copy is in `src/main/proto`).

### 0.2 Version alignment (A1)
- All services → Boot **4.0.9**.
- `api-gateway`: Spring Cloud **2025.1.4**; `spring-cloud-starter-gateway-server-webflux`; rename route prefix to `spring.cloud.gateway.server.webflux.routes` in `application.yml` **and** `application-prod.yml` (rename only, nothing else in the prod file).
- `patient-service`, `analytics-service`: replace `spring-kafka:3.3.0` with `spring-boot-starter-kafka`.
- `patient-service`: remove `net.devh:grpc-spring-boot-starter` — patient is only a gRPC *client* and already builds its channel manually; it only needs `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`.
- `auth-service`, `patient-service`: springdoc **3.0.3**.
- `auth-service`: add `spring-boot-starter-validation` (fixes B6 — `@Valid` on login starts working).
- `docker-compose.yml`: pin `apache/kafka:4.3.2`.

⏸ **Checkpoint A** — `docker compose up --build`, run `Integration-tests`, open `/api-docs/patients` and `/api-docs/auth` through the gateway.

### 0.3 One source of truth for Protobuf contracts (A3)
- Move contracts to a root `proto/` folder: `proto/billing_service.proto`, `proto/patient_event.proto` (the current `kafka_service.proto`, renamed to describe what it contains — package and generated class names stay the same).
- Each service's `protobuf-maven-plugin` points at `${project.basedir}/../proto` and **includes only the files it needs** (patient: both; billing: billing; analytics: event).
- Delete the per-service copies.
- Because Docker builds can't see files outside their build context, `docker-compose.yml` changes each service's build to `context: .` + `dockerfile: <service>/Dockerfile`, and each Dockerfile copies `proto/` next to the service.
- Trade-off explained: alternative is a separate `proto-contracts` Maven artifact (needs `mvn install` / a registry first). A shared folder is simpler with no parent POM; we can revisit when CI publishes artifacts.

### 0.4 Health checks
- Add `spring-boot-starter-actuator` to every service; expose only `health` and `info`; enable liveness/readiness probes (`management.endpoint.health.probes.enabled=true`) — these become Kubernetes probes in Phase 7.
- Compose `healthcheck` for each app service (busybox `wget` against `/actuator/health/readiness`) and for Kafka (`kafka-broker-api-versions.sh`).
- `depends_on` uses `condition: service_healthy` (patient waits for Kafka + billing; gateway waits for auth + patient; analytics waits for Kafka).
- `restart: unless-stopped` for app services.

### 0.5 Flyway migrations (A2, S5)
- **patient-service**: `db/migration/V1__create_patient_table.sql` matching today's schema exactly; `spring.flyway.baseline-on-migrate=true` so an existing volume (created by Hibernate) is recorded as version 1 instead of failing; `ddl-auto: validate`; `sql.init.mode: never`.
- **auth-service**: `db/migration/V1__create_users_table.sql`; the test user moves from `data.sql` to `db/dev-data/R__dev_test_user.sql`, loaded **only** when `SPRING_FLYWAY_LOCATIONS` includes `classpath:db/dev-data` — set in `docker-compose.yml` (local dev). Without it (prod), no seeded admin. `data.sql` deleted.
- Explain: why the users table also needs a migration even though Auth0 arrives in Phase 3 (auth-service must keep working until then).

⏸ **Checkpoint B** — test both with your existing volumes *and* with a fresh `docker compose down -v`; inspect `flyway_schema_history` in each DB.

### 0.6 Patient API correctness (B4, B5, B11, A6)
- Errors return **RFC 9457 `ProblemDetail`** (`spring.mvc.problemdetails.enabled=true` + handler):
  - validation failure → 400 with a field-error map;
  - malformed JSON / invalid date → 400 (was 500);
  - patient not found → **404** (was 400);
  - duplicate email → **409** (was 400), including the concurrent case by translating `DataIntegrityViolationException` (B11).
- `PatientRequestDTO`: `@Email` on email; `birthDate`/`registrationDate` become `LocalDate` (`@Past` for birth date) — the JSON format (`"1998-04-23"`) does not change.
- `GET /patients` becomes paginated: `?page=0&size=20`, max size 100, sorted by name; response uses Spring Data's stable `PagedModel` JSON (`{ "content": [...], "page": {...} }`).
  ⚠️ This changes the response shape from a bare array to an object — the integration test and `.http` file are updated in 0.10.
- Remove the unused `KafkaTemplate` field from `PatientService` (A4).

### 0.7 Patient-creation flow (B1 interim, B2, B3, A4)
- **gRPC deadline**: every billing call gets a deadline (default 3 s, configurable `BILLING_SERVICE_DEADLINE`); the channel is closed on shutdown (`@PreDestroy`).
- **Billing failure**: `createPatient` becomes `@Transactional`; if billing fails (`StatusRuntimeException`), the patient insert is **rolled back** and the client gets **503 Service Unavailable** (Problem Detail). Retrying later then works because no orphan patient exists.
- **Kafka after commit**: the service publishes a `PatientCreated` application event; a `@TransactionalEventListener(phase = AFTER_COMMIT)` sends it to Kafka — so we never announce a patient whose transaction rolled back.
- **Kafka send**: key = patient id (per-patient ordering); success/failure is logged from the `CompletableFuture` callback (B2).
- Remove the duplicated Java Kafka config in patient and analytics (`KafkaProducerConfig`, `KafkaConfig`); `application.yml` + Boot auto-configuration become the single place (A4). Add `spring.kafka.bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`.
- Explain the **remaining gap** honestly: if the app crashes between commit and Kafka send, the event is lost; if billing succeeds and the DB commit fails, billing has an orphan account. Phase 4's outbox closes both.

### 0.8 Gateway error handling (B8)
- Remove `JwtValidationException` (`@RestControllerAdvice` never runs for gateway filters).
- Handle errors inside `JwtValidationGatewayFilterFactory`: auth-service 401/403 → **401**; auth-service unreachable/5xx → **503**; plus a timeout on the `/validate` call.
- Note: this filter is replaced by Auth0 resource-server validation in Phase 3 — kept minimal on purpose.

⏸ **Checkpoint C** — stop billing (`docker compose stop billing-service`) and create a patient → expect 503 and no DB row; start it again → create succeeds; send a bad token → 401.

### 0.9 Remove PII from logs (S7)
- Producer, consumer, billing and gRPC client log **patient id / event type only** — no name, email, address.
- `show-sql` default `false` in patient (opt-in via env for debugging).

### 0.10 Docs, request files, E2E test
- Fix `api-requests/*.http` (B10): token header placement, token on create/update, pagination example.
- Fix `PatientIntegrationTest` (B9): assert on `content` and `page` of the paginated response.
- README: remove the outdated CDK/LocalStack sections, document current compose setup, ports, health endpoints, link to `docs/implementation-plans/roadmap.md`.
- Update `CLAUDE.md` (versions, proto location, Flyway, dev seed data).
- Update the Progress table in `roadmap.md`.

## Verification (definition of done)

1. Every service builds: `docker compose build` succeeds.
2. `docker compose up` → every container reaches **healthy** (`docker compose ps`).
3. `Integration-tests` pass against the running stack.
4. Manual checks via `.http` files:
   - unknown patient id → 404; duplicate email → 409; invalid email/date → 400; all as Problem Detail JSON;
   - `GET /api/patients?page=0&size=5` returns a page;
   - billing stopped → create returns 503, no row in PatientDB;
   - invalid/expired token → 401.
5. `flyway_schema_history` exists in both databases; patient-service starts with `ddl-auto: validate`.
6. `docker compose logs` contains no patient names or emails.
7. Starting auth-service without the dev-data location → no `testuser@test.com` in the DB.

## Exercises (for you, after the phase)

1. Add a `phone_number` column to patients **the Flyway way**: write `V2__add_patient_phone.sql`, add the field to entity and DTOs, rebuild, check `flyway_schema_history`. What happens if you edit `V1` instead? (Try it, read the error, revert.)
2. Run two `POST /api/patients` with the same email at the same time (two terminals with `curl`). Which status codes do you get, and which code path produced each?
3. Set `BILLING_SERVICE_DEADLINE` to `1ms` and create a patient. Explain the response and why the DB has no row.
4. Explain in your own words: why is sending to Kafka *after commit* better than *inside* the transaction, and what can still go wrong?
5. Run `docker compose ps` while the stack starts. In which order do services become healthy, and why?

## Risks

| Risk | Mitigation |
|---|---|
| Gateway 3.4 → Boot 4 / Gateway 5 upgrade breaks routing | Done first (0.2) and verified at Checkpoint A before anything else changes |
| `net.devh` gRPC server starter misbehaves on Boot 4.0.9 in billing | It already runs on 4.0.2; if 4.0.9 breaks it, pull the Spring gRPC migration forward into this phase |
| Flyway V1 doesn't match an existing Hibernate-created table | `ddl-auto: validate` fails fast at startup and names the mismatch; worst case for local dev: `docker compose down -v` |
| Pagination changes the `GET /patients` response shape | Only consumers are our own test and `.http` files, updated in the same phase |
| Build context change (0.3) slows Docker builds (whole repo sent) | Root `.dockerignore` excludes `target/`, `.git/`, `.idea/`, `docs/` |

## What changed

*(filled in at the end of the phase)*
