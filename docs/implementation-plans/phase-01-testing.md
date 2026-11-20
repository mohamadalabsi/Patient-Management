# Phase 1 — Testing safety net

| | |
|---|---|
| Branch | `feature/phase-01-testing` (from `dev-branch` after PR #2) |
| Depends on | Phase 0 |
| Status | ✅ done (2026-11-17) |
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

## How to read this file

You should understand the whole phase from this file alone, without opening the code.
The **Technology map** says which tools were used and why. **Steps** says, for every step,
*what* was done, *why*, *how* (which technology and how it works), *when* you would use the same
technique in another project, and *how it was verified*. The code comments (`PHASE 1 (step 1.x)`)
then explain the details line by line.

## Technology map

| Technology | What it is | Why we use it here | Steps |
|---|---|---|---|
| **JUnit 5 (Jupiter 6.x)** | The test framework: finds and runs test methods, `@Test`, `@Nested`, `@BeforeEach` | Standard for Java; every other tool plugs into it | all |
| **AssertJ** | Fluent assertions: `assertThat(x).isEqualTo(y)` | Readable checks and clear failure messages | all |
| **Mockito** | Creates fake objects (mocks) and records how they were called | Unit tests of one class without its database or network | 1.3, 1.4, 1.9 |
| **Spring Boot test slices** (`@WebMvcTest`, `@DataJpaTest`) | Start only one layer of the application | Test HTTP or JPA behaviour in seconds, not the whole app | 1.4, 1.5, 1.9 |
| **`@SpringBootTest`** | Starts the whole service as in production | Prove that the pieces work together (transactions, Kafka, gRPC) | 1.2, 1.6, 1.8, 1.9, 1.10 |
| **Testcontainers** | Starts real Docker containers (Postgres, Kafka) from test code and removes them afterwards | Test against the same database and broker as production, not an imitation like H2 | 1.1, 1.2, 1.5, 1.6, 1.8, 1.9 |
| **`@ServiceConnection`** | Spring Boot reads the container's host/port/user and configures the app | No hand-written URLs or ports in tests | 1.2 |
| **Fakes** (`FakeBillingServer`, `StubHttpServer`) | Small real servers in the test that pretend to be another service | Control the other service's behaviour: OK, error, too slow | 1.6, 1.10 |
| **In-process gRPC** (`grpc-inprocess`) | A gRPC channel that runs in memory, no network port | Test a gRPC API quickly with the real generated client | 1.7 |
| **Awaitility** | Waits until a condition becomes true, with a timeout | Kafka is asynchronous; never `Thread.sleep` | 1.6, 1.8 |
| **`OutputCaptureExtension`** | Captures what the application logs | Check that no patient data (PII) is logged | 1.8 |
| **REST Assured** | A Java DSL for HTTP calls and checks | End-to-end tests against the running stack through the gateway | 1.11 |
| **Maven Surefire + Docker** (`scripts/mvn-test.sh`) | Surefire runs the tests during `mvn test`; the script runs Maven inside a container | No JDK on the host; same command later in CI | 1.1 |

### Test levels in this repo

| Level | Starts | Speed | Proves | Example |
|---|---|---|---|---|
| Unit | one class | ms | business rules | `PatientServiceTest` |
| Slice | one Spring layer | 1-3 s | HTTP contract, SQL/constraints | `PatientControllerTest`, `PatientRepoTest` |
| Integration | whole service + containers + fakes | 5-15 s | transactions, events, timeouts | `PatientCreationFlowIntegrationTest` |
| End-to-end | whole compose stack | minutes (stack) | a user journey works | `PatientJourneyIntegrationTest` |

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

Each step is one commit and was verified by running the tests through Docker (`scripts/mvn-test.sh`).
Every step answers: **What** · **Why** · **How** · **When** (when to use the same technique elsewhere) · **Verified**.

### 1.0 Claude Code settings + Phase 0 skill ✅

- **What:** `.claude/settings.json` (shared permissions) and the skill `service-conventions`.
- **Why:** Phase 0 was already merged, so its skill had to come first here. From now on every phase ends with its own skill.
- **How:** permissions as allow/deny lists (commit allowed; push, merge, branch changes and `.env` writes denied); reading `.env` only in the gitignored `settings.local.json`.
- **When:** at the start of any repo where an AI assistant works: decide what it may do alone.
- **Verified:** commits work without a prompt, a push is refused.

### 1.1 Test toolchain - running tests without a local JDK

- **What:** test dependencies in all 5 POMs, and `scripts/mvn-test.sh <service>`.
- **Why:** no JDK on the host, and Testcontainers needs Docker. One command must work the same for everyone and later in CI.
- **How:**
  - The script starts `maven:3.9.9-eclipse-temurin-21`, mounts the service and `proto/`, a Maven cache volume (`pm-m2-cache`) and the **Docker socket**.
  - With the socket, Testcontainers inside the Maven container starts *sibling* containers on the host Docker; `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` tells it how to reach them.
  - It always runs `mvn clean test` (stale `target/` files once broke a test).
  - Dockerfiles keep `-DskipTests`: running containers inside `docker build` would need Docker-in-Docker. Building and testing are separate steps.
- **When:** whenever tests need Docker but run inside a container (CI runners, dev containers).
- **Verified:** a throwaway test started a Postgres container from inside the Maven container (then removed).

### 1.2 `contextLoads` in every service (B7)

- **What:** the empty `contextLoads` test now really starts each service.
- **Why:** it failed without a running database, so tests were skipped everywhere. A green start-up test is the base for all others.
- **How:**
  - One shared `TestcontainersConfiguration` per service declares the containers as beans with `@ServiceConnection`: patient = Postgres + Kafka, auth = Postgres, analytics = Kafka.
  - billing needs nothing external (gRPC server in-process); the gateway only needs its routes.
  - Flyway runs the real migrations and Hibernate `validate` compares the entities, so the test also proves *entities match the schema*.
- **When:** in every Spring service, as the first test: it catches broken configuration, missing beans and schema drift.
- **Verified:** 5/5 green; the stack can be stopped.

### 1.3 patient-service - unit tests

- **What:** `PatientServiceTest` (11 tests with the mapper) and `PatientMapperTest`.
- **Why:** business rules (duplicate email, order of save -> billing -> event, not-found) should be checked in milliseconds.
- **How:**
  - JUnit + Mockito, **no Spring**: `@Mock` repository, gRPC client and event publisher, `@InjectMocks` service.
  - `InOrder` proves the order save -> billing -> event; `verify(..., never())` proves that nothing is saved or sent on errors.
  - Mockito is loaded as a `-javaagent` via surefire `argLine`, because self-attach fails on Java 21 in the container.
- **When:** for every class with logic. The cheapest and fastest test; write most tests at this level.
- **Verified:** all green; removing a check in the service makes a test fail.

### 1.4 patient-service - web slice (`@WebMvcTest`)

- **What:** `PatientControllerTest`, 15 tests for the HTTP contract from Phase 0.
- **Why:** status codes, validation errors and the JSON error format are the API promise to clients.
- **How:** `@WebMvcTest(PatientController.class)` starts only controllers, JSON and the exception handler. `@MockitoBean` replaces the service. `MockMvc` sends fake requests; `jsonPath` checks the body. Covers 201/200/204, 400 with an `errors` map, malformed JSON, bad UUID, unknown sort field, 404, 409, 503, `application/problem+json`, page size capped at 100.
- **When:** for every endpoint change: request/response shape, validation, status code.
- **Verified:** 15/15 green in ~2 s of test time.

### 1.5 patient-service - repository slice (`@DataJpaTest` on real Postgres)

- **What:** `PatientRepoTest`, 6 tests.
- **Why:** queries and DB constraints only behave correctly on the real database. The unique constraint is what turns a race into 409 instead of 500.
- **How:** `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` (do not swap in H2) + `@Import(PostgresTestConfiguration.class)` (a `postgres:16` container). Checks `existsByEmail...`, the unique constraint, the Flyway history table, and an unknown sort field.
- **When:** for custom queries, constraints and migrations. Use the same database as production.
- **Verified:** 6/6 green.

### 1.6 patient-service - integration test of the creation flow

- **What:** `PatientCreationFlowIntegrationTest`, 6 tests + the fake `FakeBillingServer`.
- **Why:** the most important Phase 0 promise: billing fails -> rollback and **no** Kafka event; success -> exactly one event, keyed by the patient id, sent **after commit**.
- **How:**
  - `@SpringBootTest` + `@AutoConfigureMockMvc` with Postgres and Kafka containers.
  - `FakeBillingServer` is a real gRPC server on a random port, with modes OK / UNAVAILABLE / SLOW (10 s). `@DynamicPropertySource` points the service at it and sets the deadline to 2 s.
  - A Kafka consumer in each test reads the `patient` topic, filtering by the test's unique email; Awaitility waits for the event.
  - Cases: happy path, billing unavailable -> 503 + 0 rows + 0 events, billing too slow -> 503 within the deadline, retry after failure works, 409, bad sort -> 400.
- **When:** for anything that crosses boundaries: transactions, messaging, timeouts, calls to other services.
- **Verified:** green; the mutation check (below) proved it catches a missing `@Transactional` and a wrong Kafka key.

### 1.7 billing-service - gRPC API

- **What:** `BillingGrpcServiceTest` + `contextLoads`.
- **Why:** the gRPC response is a contract that patient-service depends on.
- **How:** the gRPC server starter runs in-process (`grpc.server.in-process-name=billing-test`, `grpc.server.port=-1`). The test calls it through the real generated stub over `InProcessChannelBuilder`: no ports, no network.
- **When:** to test any gRPC server API.
- **Verified:** 3/3 green.

### 1.8 analytics-service - Kafka consumer

- **What:** `KafkaConsumerIntegrationTest`.
- **Why:** prove the consumer reads the protobuf event, logs only IDs, and survives a bad message.
- **How:** Kafka container; the test sends real protobuf bytes; `OutputCaptureExtension` captures the log and Awaitility waits until the patient id appears; it asserts that the name and email are **not** in the log. A malformed message (`0x0A 0xFF`) is skipped and the next one is still consumed.
- **When:** for every message consumer: parsing, side effects, bad input.
- **Verified:** green. Finding: the malformed message is lost (dead-letter topic in Phase 4).

### 1.9 auth-service - unit, web slice, integration

- **What:** 23 tests: `JwtUtilTest`, `AuthServiceTest`, `AuthControllerTest`, `AuthFlowIntegrationTest`.
- **Why:** login and token checks are security-critical; tampered or expired tokens must be rejected.
- **How:**
  - `JwtUtilTest`: tokens that are valid, tampered, expired, or signed with another secret.
  - `AuthServiceTest`: Mockito repository + **real** BCrypt (fast, and mocking it would prove nothing).
  - `AuthControllerTest`: `@WebMvcTest` + `@Import(SecurityConfig.class)`; needs `spring-boot-starter-security-test` in Boot 4.
  - `AuthFlowIntegrationTest`: Postgres container, a user created in the test logs in, then `/validate` accepts the token. It also proves that **no dev user** exists without the dev-data Flyway location.
  - The test secret is a constant in test code, not from `.env`.
- **When:** for any authentication code: test the rejection cases more than the happy path.
- **Verified:** 23/23 green.

### 1.10 api-gateway - JWT filter

- **What:** `GatewayJwtFilterTest` (8 tests) + `StubHttpServer`.
- **Why:** the gateway decides 401 vs 503 and must answer within its timeout even if auth-service hangs (B8).
- **How:** the gateway runs on a random port (`RANDOM_PORT`) and `WebTestClient` calls it. `StubHttpServer` (JDK `HttpServer`, random port) plays both auth-service and patient-service; `@DynamicPropertySource` sets their URLs and a 1 s auth timeout. Cases: forward with prefix stripped, missing/invalid token -> 401, auth 5xx/hang/unreachable -> 503, `/auth/**` is public.
- **When:** for gateway filters and routes, and any HTTP client: simulate the other side with a stub server.
- **Verified:** 9/9 green (with `contextLoads`); removing the timeout makes the hang test fail.

### 1.11 End-to-end tests (`Integration-tests/`)

- **What:** `PatientJourneyIntegrationTest` (6) + the 3 older tests, helper `E2E.java`.
- **Why:** prove the real, deployed stack works together (gateway -> auth -> patient -> billing -> Kafka).
- **How:** REST Assured calls the gateway at `gateway.url`. Login -> create -> list -> update -> delete, plus 400/401/404/409. Unique emails per test. The project moved to Java 21 and REST Assured 5.5.7. It runs in a Maven container against `docker compose up`.
- **When:** only for the few main journeys; everything else belongs in the faster levels.
- **Verified:** 9/9 green against freshly rebuilt images.

### Verification - mutation check (by hand)

Three bugs were put into production code on purpose, the tests were run, and the bugs were reverted:
no `@Transactional` -> 4 tests fail; wrong Kafka key -> 1 fails; gateway timeout removed -> 1 fails.
It also exposed a flaky test of ours (deadline too short in a cold JVM), fixed in `529de27`.

### 1.12 Docs - README "Testing", CLAUDE.md, roadmap, this file

### 1.13 Phase 1 skill `testing-conventions`

## Verification (definition of done)

- [x] `mvn test` passes in all 5 services (through the Docker command from 1.1) with **no compose stack running**
      (and also while it runs - no port clashes)
- [x] No test needs `.env`, a running service, or a fixed port
- [x] Every behaviour in the Phase 0 smoke test (status codes, rollback, after-commit event, gateway 401/503) has
      at least one automated test
- [x] Deliberately breaking things makes a test fail (checked by hand, then reverted):
  - remove `@Transactional` → the rollback test fails
  - change the Kafka key → the key assertion fails
  - drop the gateway timeout → the 503 test fails
- [x] `Integration-tests` pass against the full compose stack (9/9, freshly rebuilt images)
- [x] Total runtime noted per service, so we know the budget for CI (README "Testing")

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

| Step | Commit | Result |
|---|---|---|
| 1.0 | `7797aa0` | `.claude/settings.json` (permissions), Phase 0 skill `service-conventions`; `CLAUDE.md` phase workflow (`5c05738`) |
| plan | `9303f88` | this file |
| 1.1 | `70e5baf` | test dependencies in all POMs, `scripts/mvn-test.sh`; Testcontainers proven inside the maven container |
| 1.2 | `75e73bf` | `contextLoads` runs in all 5 services (**B7 fixed**); gateway got its first test |
| 1.3 | `66703d0` | 11 unit tests (service rules, mapper); Mockito as `-javaagent` in all POMs |
| 1.4 | `b8863e3` | 15 `@WebMvcTest` tests: the HTTP contract of the patient API |
| 1.5 | `76f014b` | 6 `@DataJpaTest` tests on real Postgres (unique constraint, Flyway, sort errors) |
| 1.6 | `0032aa4` | 6 integration tests: rollback, after-commit Kafka event keyed by id, gRPC deadline, retry |
| 1.7 | `d580bfa` | billing gRPC API over in-process transport |
| 1.8 | `321f960` | analytics consumer on real Kafka; malformed message skipped; no PII |
| 1.9 | `f6cd0f2` | 23 auth tests: JWT security cases, login rules, contract, real-DB login |
| 1.10 | `4c7a6a2` | 8 gateway filter tests incl. 503 after timeout (B8) |
| 1.11 | `1e4d77d` | E2E journeys (9 tests), Integration-tests on Java 21 / REST Assured 5.5.7 |
| verify | `529de27` | flaky deadline fixed; mutation check (3 deliberate bugs, all caught) |
| 1.12 | `bf33b8c` | README "Testing", CLAUDE.md, roadmap |
| 1.13 | `1220d3c` | skill `testing-conventions` |
| rework | (after review) | this file restructured (technology map; What/Why/How/When/Verified per step); both skills made stack-agnostic; CLAUDE.md plan rules |

**Totals:** 77 tests in the services (patient 39, auth 23, gateway 9, billing 3, analytics 3) + 9 end-to-end.
Runtime per service 25-80 s including Maven start and `clean`; all 5 together ~3.5 min.
No production code (`src/main`) was changed in this phase.

### Things learned while implementing (not in the plan)

- **Stale `target/` files**: an old `target/classes/application.properties` (from before Phase 0) overrode
  `application.yml` in auth tests (`sql.init.mode=always`). Maven never deletes outputs of removed sources →
  the test script always runs `mvn clean test`.
- **Mockito on Java 21** cannot self-attach its agent inside the container → loaded with `-javaagent` via
  surefire `argLine` (with an empty `argLine` property so JaCoCo can add its agent in Phase 2).
- **Boot 4 test slices are modular too**: `@WebMvcTest` needs `spring-boot-starter-webmvc-test`, security in
  the slice needs `spring-boot-starter-security-test`, `WebTestClient` needs `spring-boot-starter-webflux-test`.
- **Surefire ignores `*IT` classes** (they belong to Failsafe) → integration tests are named `*IntegrationTest`.
- **Timing assertions** must allow for JVM warm-up: the first gRPC call in a cold JVM can take > 0.5 s.
  Use a slow fake far above the deadline and a generous upper bound.

### Findings (production code - not changed, needs a decision)

| Finding | Where | Suggested fix |
|---|---|---|
| Gateway Problem Details `instance` is the **stripped** path (`/patients`), not the URI the client called (`/api/patients`), because `StripPrefix` runs before `JwtValidation` | `api-gateway` route filters / `JwtValidationGatewayFilterFactory.reject` | use the original request URL (`GATEWAY_ORIGINAL_REQUEST_URL_ATTR`) or put `JwtValidation` before `StripPrefix`; the test documents current behaviour |
| `/validate` without `Authorization` header returns **400** (Spring's missing-header error) | `auth-service` `AuthController` | harmless today (gateway checks the header first, maps 4xx → 401); goes away with Auth0 in Phase 3 |
| billing accepts an **empty request** and always returns account `12345` | `billing-service` `BillingGRPC` | billing is still a stub; validation + real accounts when billing gets a database |
| A malformed Kafka message is logged and **lost** | `analytics-service` `KafkaConsumer` | dead-letter topic in Phase 4 |

### Still open (planned)

- Running the tests automatically on every push + coverage report → **Phase 2 (CI)**.
- Security tests for Auth0 tokens / roles → **Phase 3**.
- Outbox, retries, DLT tests → **Phase 4**.
