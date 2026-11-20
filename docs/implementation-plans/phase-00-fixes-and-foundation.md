# Phase 0 — Fixes & Foundation

> Status: **done** (2026-11-16)
> Branch: `feature/phase-00-fixes-and-foundation`
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

## How to read this file

You should understand the whole phase from this file alone, without opening the code.
The **Technology map** says which tools were added or changed and why. **Steps** says, for every step,
*what* was done, *why*, *how* (which technology and how it works), *when* you would use the same
technique in another project, and *how it was verified*. The code comments (`PHASE 0 (step 0.x)`)
then explain the details line by line.

## Technology map

| Technology | What it is | Why we use it here | Steps |
|---|---|---|---|
| **Spring Boot BOM / parent** | A list of library versions that are tested together | Stop hand-pinning versions; one upgrade moves everything consistently | 0.2 |
| **Spring Cloud release train** (2025.1.x) | The same idea for Spring Cloud (Gateway) | The gateway was on Boot 3.4; the train for Boot 4.0 brings Gateway 5 | 0.2 |
| **Spring Cloud Gateway (server-webflux)** | Reactive API gateway: routes, filters | Single entry point; JWT check before patient-service | 0.2, 0.8 |
| **Protocol Buffers + `protobuf-maven-plugin`** | Contract language for gRPC and Kafka messages; the plugin generates Java at build time | One contract source in `proto/` instead of copies that drift | 0.3 |
| **Spring Boot Actuator** | Built-in operational endpoints (`/actuator/health`) | Liveness/readiness so Docker (later Kubernetes) knows when a service is ready | 0.4 |
| **Docker Compose healthchecks + `depends_on: service_healthy`** | Compose runs a command to check a container and waits for it | Services start in the right order, only when dependencies are really ready | 0.4 |
| **Flyway** | Versioned SQL migrations, history in `flyway_schema_history` | The schema is code: reviewed, versioned, the same everywhere; Hibernate only validates | 0.5 |
| **Bean Validation** (`spring-boot-starter-validation`) | Annotations like `@NotBlank`, `@Email` checked on request DTOs | Invalid input becomes 400 at the edge instead of 500 in the database | 0.2, 0.6 |
| **RFC 9457 Problem Details** (`ProblemDetail`) | Standard JSON error format, `application/problem+json` | Every error looks the same for clients | 0.6, 0.8 |
| **Spring Data pagination** (`Pageable`, `PagedModel`) | Page/size/sort parameters and a stable page JSON | Lists cannot grow without limit | 0.6 |
| **gRPC deadlines** (`withDeadlineAfter`) | The maximum time a remote call may take | A hanging billing service must not hang patient creation | 0.7 |
| **`@Transactional` + `@TransactionalEventListener(AFTER_COMMIT)`** | DB transaction with rollback; an event handler that runs only after a successful commit | No half-created patients; no Kafka event for a rolled-back patient | 0.7 |
| **Kafka (spring-kafka via `spring-boot-starter-kafka`)** | Message broker; producer keyed by patient id | Per-patient ordering; config by Boot instead of Java classes | 0.2, 0.7 |
| **WebClient + Reactor `timeout`** | Reactive HTTP client used by the gateway filter | Auth-service down -> 503 after 3 s instead of 500 after 30 s | 0.8 |

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

Each step is one commit and was verified against the running compose stack. At the ⏸ **checkpoints** the
whole stack was run and tested by hand. Every step answers: **What** · **Why** · **How** · **When** · **Verified**.

### 0.1 Repository hygiene

- **What:** root `.gitignore` (`target/`, `.idea/`, `*.iml`, OS files); build output and IDE files untracked;
  the dead IntelliJ `Main.java` and the stray `analytics-service/src/main/kafka_service.proto` (A3) disabled.
- **Why:** generated files in git cause noisy diffs and merge conflicts; dead files confuse readers.
- **How:** `git rm --cached` removes files from git but keeps them on disk; `.gitignore` stops them coming back.
- **When:** at the start of any repo, and whenever build output shows up in `git status`.
- **Verified:** `git status` clean after a build.

### 0.2 Version alignment (A1, B6)

- **What:** all services on Spring Boot **4.0.9**; gateway on Spring Cloud **2025.1.4** with
  `spring-cloud-starter-gateway-server-webflux`; Kafka via `spring-boot-starter-kafka`; springdoc **3.0.3**;
  validation starter in auth; Kafka image pinned to `apache/kafka:4.3.2`.
- **Why:** the gateway was on Boot 3.4 and the rest on 4.0.x; hand-pinned libraries (`spring-kafka:3.3.0`)
  were Boot-3-era. Mixed versions break in surprising ways. `@Valid` on login did nothing without the
  validation starter (B6). `latest` image tags change under you.
- **How:**
  - The Boot parent POM (a BOM) chooses compatible versions, so most `<version>` tags were removed.
  - Gateway 5 renamed its artifact and its route properties to `spring.cloud.gateway.server.webflux.routes`.
  - Boot 4 is modular: each feature needs its own starter (Kafka, Flyway, WebClient).
  - patient-service dropped the `net.devh` gRPC server starter because it is only a gRPC *client*.
- **When:** whenever services drift apart in versions; on every framework upgrade.
- **Verified:** ⏸ **Checkpoint A** - `docker compose up --build`, E2E tests green, `/api-docs/*` through the gateway.

### 0.3 One source of truth for Protobuf contracts (A3)

- **What:** root `proto/` with `billing_service.proto` and `patient_event.proto`; the per-service copies
  are no longer compiled.
- **Why:** three copies of the same contract drift apart, and a mismatch only fails at runtime.
- **How:**
  - Each service's `protobuf-maven-plugin` uses `<protoSourceRoot>${project.basedir}/../proto</protoSourceRoot>`
    and `<includes>` only the files it needs; Java classes are generated into `target/` at build time.
  - Docker cannot see files outside the build context, so compose builds these services with the
    **repo root** as context (`dockerfile: <svc>/Dockerfile`) and a root `.dockerignore` keeps it small.
  - Alternative (a published `proto-contracts` artifact) was rejected for now: it needs a registry.
- **When:** whenever two services share a message format: keep the contract in exactly one place.
- **Verified:** gRPC call and Kafka event still flow end-to-end.

### 0.4 Health checks

- **What:** Actuator in every service (only `health` and `info` exposed, liveness/readiness probes);
  compose healthchecks for every app and Kafka; `depends_on: condition: service_healthy`; `restart: unless-stopped`.
- **Why:** "container started" is not "service ready". Without this, patient-service started before Kafka
  or billing were usable.
- **How:** compose runs `wget -qO- http://localhost:<port>/actuator/health/readiness` (Kafka:
  `kafka-broker-api-versions.sh`); a service starts only when its dependencies report healthy. Exposing only
  `health,info` keeps `/actuator/env` (which contains config) closed.
- **When:** in every deployed service; these endpoints become Kubernetes probes in Phase 7.
- **Verified:** `docker compose ps` all healthy; `/actuator/env` -> 404.

### 0.5 Flyway migrations (A2, S5)

- **What:** `V1__create_patient_table.sql` and `V1__create_users_table.sql`; Hibernate `ddl-auto: validate`;
  the test user moved to `db/dev-data/R__dev_test_user.sql`, loaded only in local compose.
- **Why:** with `ddl-auto: update` Hibernate silently changed the schema - nothing versioned, nothing
  reviewable. And a seeded admin user would have reached production (S5).
- **How:**
  - Flyway runs `V<n>__*.sql` files once, in order, and records them in `flyway_schema_history`.
  - `baseline-on-migrate: true` marks an existing database (created earlier by Hibernate) as version 1
    instead of failing.
  - `validate` makes Hibernate compare entities to the schema and refuse to start on a mismatch.
  - The dev user lives in a separate location that is enabled only by `SPRING_FLYWAY_LOCATIONS` in
    `docker-compose.yml`; `R__` = repeatable migration.
- **When:** in every service that owns a database, from day one.
- **Verified:** ⏸ **Checkpoint B** - old volumes baselined, fresh volumes migrated; a prod-like start has 0 users.

### 0.6 Patient API correctness (B4, B5, B11, A6)

- **What:** Problem Details errors; correct status codes (400 / 404 / 409); typed, validated DTOs;
  paginated `GET /patients`; unused `KafkaTemplate` removed.
- **Why:** not-found returned 400, duplicate email 400, a bad email even **500** (the DB rejected it). Clients
  cannot react correctly to wrong codes, and an unlimited list is a performance risk.
- **How:**
  - `GlobalExceptionHandler extends ResponseEntityExceptionHandler` returns `ProblemDetail`; validation errors
    add an `errors` map.
  - DTO fields are `LocalDate` with `@Email`, `@Past`; JSON format unchanged.
  - Duplicate email: `existsByEmail` check for a friendly 409 **plus** the DB unique constraint, with
    `DataIntegrityViolationException` -> 409 for the race (B11).
  - `Pageable` + `@PageableDefault`, response `PagedModel` (`{content, page}`), max size 100.
- **When:** for every REST API: status codes and one error format are part of the contract.
- **Verified:** 10 parallel duplicate creates -> 1x201 + 9x409 and 1 row; E2E assertion updated (B9).

### 0.7 Safe patient-creation flow (B1 interim, B2, B3, A4)

- **What:** gRPC deadline, rollback on billing failure (503), Kafka event only after commit, keyed by
  patient id, send result logged; Kafka configured only in `application.yml`.
- **Why:** a billing failure left an orphan patient (retry then gave "duplicate"); a hanging billing call
  hung the request; an event could be sent for a patient that was rolled back.
- **How:**
  - `withDeadlineAfter(BILLING_SERVICE_DEADLINE, default 3 s)` on every call; `StatusRuntimeException` ->
    `BillingServiceUnavailableException` -> 503. Channel closed in `@PreDestroy`.
  - `createPatient` is `@Transactional`: save -> billing -> publish an in-JVM `PatientCreatedEvent`.
  - `PatientEventListener` with `@TransactionalEventListener(AFTER_COMMIT)` sends to Kafka only after a
    successful commit; `send(...).whenComplete(...)` logs partition/offset or the error.
  - The Kafka Java config classes are commented out; Boot auto-configures from `application.yml`.
  - **Remaining gap** (documented): crash between commit and send loses the event; Phase 4's outbox closes it.
- **When:** whenever one request writes to a DB *and* calls another system (the dual-write problem).
- **Verified:** billing stopped or frozen -> 503 after 3 s, 0 rows, no event; retry succeeds.

### 0.8 Gateway error handling (B8)

- **What:** the JWT filter returns 401 or 503 itself, with a timeout; the useless `@RestControllerAdvice` removed.
- **Why:** with auth-service down, clients waited **30 s** for a 500. `@RestControllerAdvice` never runs for
  gateway filters.
- **How:** in `JwtValidationGatewayFilterFactory`, the WebClient call to `/validate` gets
  `.timeout(AUTH_SERVICE_TIMEOUT, default 3 s)`; auth 4xx -> 401, 5xx/unreachable/timeout -> 503, body as
  Problem Details. Kept minimal: Auth0 replaces this filter in Phase 3.
- **When:** in every gateway or client of another service: timeouts and a clear mapping of upstream errors.
- **Verified:** ⏸ **Checkpoint C** - auth down -> 503 after 3 s; bad token -> 401; downstream 404 passes through.

### 0.9 No PII in logs (S7)

- **What:** producer, consumer, billing and gRPC client log IDs and event types only; `show-sql` off by default.
- **Why:** logs are copied to many systems and kept long; names and emails of patients are health-related
  personal data.
- **How:** log statements rewritten to `patientId`; `SPRING_JPA_SHOW_SQL` opt-in for debugging.
- **When:** always - decide on day one what may appear in logs.
- **Verified:** `docker compose logs` after a full smoke run contains no names or emails.

### 0.10 Docs, request files, E2E test

- **What:** README rewritten (no CDK), `api-requests/*.http` fixed (B10), `PatientIntegrationTest` asserts the
  page shape (B9), `CLAUDE.md` and roadmap updated.
- **Why:** docs that describe a system that no longer exists mislead everyone.
- **How:** documentation only.
- **When:** at the end of every phase (now step "second-to-last commit" in the workflow).
- **Verified:** E2E tests green; `.http` requests work as written.

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

Every step is one commit; each change is explained in the code with `PHASE 0 (step x.y)` comments and the replaced code is kept as `PHASE 0 (old)`.

| Step | Commit | Result (verified against the running stack) |
|---|---|---|
| 0.1 Hygiene | `caaa7f1` | Root `.gitignore`; build output and `.idea/` untracked; dead `Main.java` and stray proto disabled |
| 0.2 Versions | `a788818` | All services Boot 4.0.9; gateway Spring Cloud 2025.1.4 + `gateway-server-webflux` + `starter-webclient`; Kafka starter; springdoc 3.0.3; validation in auth (B6); `apache/kafka:4.3.2` |
| 0.3 Contracts | `dd66a49` | Root `proto/` (single source); services compile with `<protoSourceRoot>` + `<includes>`; root build context + `.dockerignore`. gRPC + Kafka flow verified |
| 0.4 Health | `ec5d4ae` | Actuator `health`/`info` only, liveness/readiness; compose healthchecks (incl. Kafka), `service_healthy`, `restart: unless-stopped`; `/actuator/env` → 404 |
| 0.5 Flyway | `d28bc17` | V1 migrations matching the existing schemas; existing DBs baselined, fresh DBs migrated; `ddl-auto: validate`; test user only via `db/dev-data` (S5) — prod-like start has 0 users |
| 0.6 API | `ef231af` | Problem Details; 404 / 409 / 400 (B4, B5); 10 parallel duplicate creates → 1×201 + 9×409, 1 row (B11); pagination, max 100 (A6); E2E assertion fixed (B9) |
| 0.7 Create flow | `0994386` | Billing stopped/frozen → 503 after 3s deadline, 0 rows, no Kafka event; retry succeeds (B1 interim, B3); Kafka keyed by patient id, result logged (B2); duplicate Kafka Java configs disabled (A4) |
| 0.8 Gateway | `5f8eb1d` | Auth-service down: **500 after 30s → 503 after 3s**; bad token 401; downstream 404 passes through (B8) |
| 0.9 PII | `6facd8e` | No names/emails in any service log after a full smoke run (S7); `show-sql` off by default |
| 0.10 Docs | `acf843e` | README rewritten (no CDK), `.http` files fixed (B10), `CLAUDE.md` updated |
| rework | (Phase 1 branch, 2026-11-20) | this file restructured: technology map; What/Why/How/When/Verified per step |

### Corrections to the audit discovered while implementing
- **B8** was partly wrong: an *invalid token* already returned 401. The real bug was worse than predicted: with auth-service *down*, clients waited **30 s** for a 500.
- **Bad email** returned **500**, not just "no validation" (B5): the entity's `@Email` failed during the INSERT.
- Boot 4's WebFlux starter no longer provides `WebClient.Builder` — the gateway needs `spring-boot-starter-webclient` (would have crashed at startup).
- In Boot 4, plain `spring-kafka` has no auto-configuration — the reason the project needed hand-written Kafka config classes.

### Still open (planned)
- B7 `contextLoads` needs a real DB → Phase 1 (Testcontainers).
- Event lost if Kafka send fails after commit; orphan billing account if commit fails after billing → Phase 4 (outbox).
- `net.devh` gRPC server starter in billing → Spring gRPC in Phase 4.
- Security S1–S4, S6, S8, S9 → Phase 3 (Auth0).
