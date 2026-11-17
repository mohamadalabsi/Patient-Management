# Phase 1 — Testing safety net

| | |
|---|---|
| Branch | `feature/phase-01-testing` (from `dev-branch` after PR #2) |
| Depends on | Phase 0 |
| Status | 🟡 in progress |
| Audit items | B7, section 4.4 "Missing tests" |

## Goal

Today every service has one empty `contextLoads` test, and even that cannot run without a
real database (B7). That is why every Dockerfile builds with `-DskipTests`. Phase 0 changed a lot of behaviour
(status codes, rollback, after-commit events, timeouts), and right now the only proof is the manual smoke
script we ran.

After this phase:

- `mvn test` works in **every** service with nothing running except Docker. No compose stack, no `.env`.
- The important behaviour from Phase 0 is pinned by automated tests, so later phases (security, outbox,
  caching, Kubernetes) cannot silently break it.
- The tests are fast enough to run in CI on every push (that is Phase 2).

### Out of scope (and where it goes instead)

| Not in this phase | Where |
|---|---|
| Running the tests automatically on push, coverage report/gate (JaCoCo) | Phase 2 (CI) |
| Security tests (JWT from Auth0, `@PreAuthorize`, 403 cases) | Phase 3 — written together with the security code |
| Outbox, retries, dead-letter topic tests | Phase 4 |
| Load / performance tests | Phase 6 |
| Consumer-driven contract tests (Pact) | not planned — the shared `proto/` folder already gives one contract source |

## Target versions (managed by the Spring Boot 4.0.9 BOM, verified 2026-11-17)

| Library | Version | Notes |
|---|---|---|
| JUnit Jupiter | 6.0.3 | via `spring-boot-starter-test` / `*-test` starters |
| Mockito | 5.20.0 | |
| AssertJ | 3.27.7 | |
| Awaitility | 4.3.0 | waiting for asynchronous things (Kafka) without `Thread.sleep` |
| Testcontainers | 2.0.5 | **2.x renamed the modules**: `testcontainers-postgresql`, `testcontainers-kafka`, `testcontainers-junit-jupiter` |
| `spring-boot-testcontainers` | 4.0.9 | `@ServiceConnection` — Boot reads the container's URL/user/password itself |
| `grpc-testing` / `grpc-inprocess` | = grpc 1.69 used by the services | in-memory gRPC server for tests |

Versions are not written in the POMs when the Boot BOM manages them. That way the next Boot upgrade updates them together.

## Concepts you will learn in this phase

- **The test pyramid.** Many fast **unit tests** (one class, collaborators mocked, milliseconds). Fewer **slice
  tests** (`@WebMvcTest`, `@DataJpaTest`: one layer of Spring, seconds). A few **integration tests** (whole service with real Postgres/Kafka). Very few **end-to-end tests** (whole stack through the gateway). The higher the level, the slower and more fragile the test, so each level only tests what the lower one cannot.
- **Mocks vs. fakes vs. real dependencies.** Mockito mocks (`@Mock`, `@MockitoBean`) for unit and slice tests. Real
  dependencies in throwaway containers (Testcontainers) where the behaviour of the real thing matters: SQL constraints, Flyway migrations, Kafka keys.
- **Spring test slices** load only part of the application context. `@WebMvcTest` loads controllers and the exception handler. `@DataJpaTest` loads JPA and repositories.
- **Spring test context caching.** Tests with the same configuration share one started context, so the
  test setup is designed to be shared rather than unique per class.
- **Testcontainers + `@ServiceConnection`.** A real Postgres/Kafka starts in Docker for the test run on a random
  port, and Boot wires the connection automatically.
- **Testing what Phase 0 promised.** Rollback on billing failure, event sent *only after commit*, 409 on duplicate
  email, gateway 503 within the timeout.
- **In-process gRPC.** A gRPC server that lives in memory, with no network and no ports.
- **Asynchronous assertions** with Awaitility (Kafka consumers run on another thread).
- **Readable tests.** Given / When / Then structure, one behaviour per test, names that read like a sentence.

## Steps

Each step is one commit, and each step is verified by running the tests through Docker. As in Phase 0, comments
in the code explain each test, tagged `PHASE 1 (step 1.x)`.

### 1.0 Claude Code settings + Phase 0 skill ✅

`.claude/settings.json` holds the shared permissions. `.claude/skills/service-conventions` holds what Phase 0 established. Done first,
because Phase 0 was already merged. From now on every phase ends with its own skill.

### 1.1 Test toolchain and how to run tests without a local JDK

- Add the test dependencies to each service: the `*-test` starters, `spring-boot-testcontainers`, and the Testcontainers modules.
- **Problem to solve:** tests run inside a `maven` container, and Testcontainers must start *sibling*
  containers. Solution: mount the Docker socket into the Maven container, and tell Testcontainers how to reach
  the containers it started (`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` on Docker Desktop).
  Prove it with a minimal test first, before writing real tests.
- One documented command (README + CLAUDE.md) to run the tests of a service.
- Dockerfiles **keep `-DskipTests`**. Running Testcontainers inside `docker build` would need
  Docker-in-Docker. Tests run as their own step (locally now, in CI in Phase 2), and the image build only packages.

### 1.2 Fix `contextLoads` in every service (B7)

- A shared test configuration per service (`TestcontainersConfiguration`) with `@ServiceConnection` containers:
  - patient: Postgres + Kafka (billing gRPC is not needed at startup, because the channel connects lazily)
  - auth: Postgres
  - analytics: Kafka
  - billing: nothing external (gRPC server on a random port)
  - gateway: nothing external (routes only)
- Flyway runs the real migrations against the container, and Hibernate `validate` checks the entities against them.
  So `contextLoads` now also proves *migrations and entities match*.

### 1.3 patient-service — unit tests

- `PatientServiceTest` (Mockito, no Spring):
  - create saves and then calls billing and then publishes the event;
  - duplicate email throws `EmailAlreadyExistException` and nothing is saved;
  - billing failure propagates and no event is published;
  - update of an unknown id gives `PatientNotFoundException`;
  - update keeping the patient's own email works.
- `PatientMapperTest`: all fields mapped, dates kept.

### 1.4 patient-service — web slice (`@WebMvcTest`)

`PatientControllerTest` with a mocked `PatientService` covers the HTTP contract from step 0.6:

- 201 + body; 400 with `errors` map (blank name, bad email, future birth date, missing registration date on create
  only); 400 for malformed JSON / bad UUID / unknown sort property; 404; 409; 503 (billing); 204 delete.
- Response type is `application/problem+json`.
- Pagination: default size 20; `size=1000` is capped at 100.

### 1.5 patient-service — repository slice (`@DataJpaTest` + Testcontainers Postgres)

- `existsByEmail`, `existsByEmailAndIdNot`.
- The DB unique constraint `uk_patient_email` really rejects a duplicate. This is the reason the race returned 409 and not 500.
- Uses the **real** Flyway migration, not an H2 in-memory DB. H2 behaves differently from Postgres, so a passing test against it would prove little.

### 1.6 patient-service — integration: the creation flow

`@SpringBootTest` with Postgres + Kafka containers and a **fake billing gRPC server**. The fake is a real gRPC server on a random port
whose behaviour each test chooses: OK / error / too slow. Tests:

- happy path: row saved, billing called, **one Kafka message on `patient`, key = patient id**;
- billing returns an error or exceeds the deadline: `BillingServiceUnavailableException`, **0 rows, 0 messages**
  (this proves the rollback and the after-commit listener);
- `BillingServiceGrpcClient` alone: deadline respected (fails after ~deadline, not 30 s).

### 1.7 billing-service — gRPC service test

- In-process gRPC server hosting `BillingGRPC`, called through a real generated stub: the response fields are correct.
- `contextLoads` (from 1.2).

### 1.8 analytics-service — Kafka consumer test

- Testcontainers Kafka. Produce a protobuf `PatientEvent` and assert with Awaitility that the consumer received
  and parsed it. A spy bean or captured log output, IDs only.
- A malformed message does not kill the consumer. If it does today, document that and leave the fix to Phase 4 (DLT).

### 1.9 auth-service — unit, web slice, integration

- `JwtUtil`: generate, then validate is valid; tampered, expired, or signed with another secret is invalid.
- `AuthService`: right password gives a token; wrong password or unknown user gives empty.
- `AuthController` (`@WebMvcTest` + security config): login 200/401/400, validate 200/401, missing header.
- Integration with Postgres: a user created in the test can log in. Without the dev-data location there are
  **0 users**, which proves the test admin never reaches production.

### 1.10 api-gateway — JWT filter tests

`JwtValidationGatewayFilterFactory` against a **stub auth server** (a local HTTP server):

- auth answers 200 → request passes through;
- 401 → 401;
- 500 → 503;
- auth unreachable / slower than the timeout → **503 within ~timeout** (the B8 fix);
- no `Authorization` header → 401;
- error body is Problem Details.

### 1.11 End-to-end tests (`Integration-tests/`)

- Extend to the main user journeys through the gateway:
  - login → create → list (paginated) → update → delete;
  - 400 / 401 / 404 / 409 cases.
- Each test creates its own data with unique emails, so tests do not depend on each other or on order.
- Update REST Assured 5.3.0 to the current 5.x.

### 1.12 Docs

README "Testing" section, CLAUDE.md commands, roadmap Progress, this file's "What changed".

### 1.13 Phase 1 skill

`.claude/skills/testing-conventions`: which test type to write for which change, naming, the Testcontainers setup,
how to run tests through Docker. Plus a row in `.claude/README.md`.

## Verification (definition of done)

- [ ] `mvn test` passes in all 5 services (through the Docker command from 1.1) with **no compose stack running**
- [ ] No test needs `.env`, a running service, or a fixed port
- [ ] Every behaviour in the Phase 0 smoke test (status codes, rollback, after-commit event, gateway 401/503) has
      at least one automated test
- [ ] Deliberately breaking things makes a test fail (checked by hand, then reverted):
  - remove `@Transactional` → the rollback test fails
  - change the Kafka key → the key assertion fails
  - drop the gateway timeout → the 503 test fails
- [ ] `Integration-tests` pass against the full compose stack
- [ ] Total runtime noted per service, so we know the budget for CI

## Exercises (for you, after the phase)

1. Write a `@WebMvcTest` for a new case: `PUT /patients/{id}` with an email that belongs to *another* patient → 409.
2. Break `PatientMapper` (forget one field) and see which tests fail and which don't. What does that tell you about coverage?
3. Add a `V2__` migration that adds a nullable `phone` column, without changing the entity. Which test fails, and why is that good?
4. Make one test flaky on purpose (`Thread.sleep` + a race) and replace it with Awaitility.
5. Explain in your own words why `@DataJpaTest` uses Postgres in a container here instead of H2.

## Risks

| Risk | Mitigation |
|---|---|
| Testcontainers inside a Maven container on WSL/Docker Desktop cannot reach its containers | Spike first (1.1); fallbacks: `TESTCONTAINERS_HOST_OVERRIDE`, or `--network host` |
| Slow test runs (each container ~2–5 s) | One shared container set per service (context caching); unit and slice tests stay container-free |
| Flaky async tests | Awaitility with timeouts, never `Thread.sleep`; unique data per test |
| Tests that only test mocks | Every important rule also has a test against the real dependency (1.5, 1.6, 1.8) |
| Refactoring production code just to make it testable | Allowed only where it is small and improves the design; explained in a `PHASE 1` comment |

## What changed

_Filled in at the end of the phase._
