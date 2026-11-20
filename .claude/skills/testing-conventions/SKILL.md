---
name: testing-conventions
description: How tests are chosen, written, structured and run for backend microservices - test levels, real dependencies in containers, fakes for other services, async and timing tests, naming, pre-commit checks. Stack-agnostic principles (part A) plus the concrete Java/Spring Boot setup of this repo (JUnit, Mockito, Testcontainers, slices, scripts) in part B1 and equivalents for other stacks in part B2. Use when adding or changing any test, when production code changes need a test, when a test fails or is flaky, or when touching test dependencies, container setup, test runner config or the end-to-end test module.
---

# Testing conventions (established in roadmap Phase 1)

**How to use this skill:** part A holds in any language. Then use the stack section that matches the
project: B1 for Java/Spring Boot (this repo), B2 for other stacks. Background:
`docs/implementation-plans/phase-01-testing.md`. Code style rules (`PHASE n` comments, old code
commented out, one commit per step) come from `service-conventions` and apply to tests too.

---

## A. Principles (any stack)

### A1. Pick the lowest level that can prove the behaviour

| Change | Level | Starts | Proves |
|---|---|---|---|
| business rule, mapper, util | **unit** | one class/function, collaborators mocked | logic, in milliseconds |
| endpoint, status code, validation, error body, pagination | **web slice** | the HTTP layer only, service mocked | the API contract |
| query, DB constraint, migration | **data slice** | data layer + real database | SQL really behaves so |
| transactions, events, calls to other services, timeouts | **integration** | whole service + real infra + fakes | the pieces work together |
| a server API (gRPC/HTTP) others depend on | **API test** | the server, called by a real client | the contract |
| a user journey across services | **end-to-end** (few!) | the whole deployed stack | it works for a client |

Most tests are unit and slice tests; go higher only for what the lower level cannot see.
**Every bug fix gets a test that fails without the fix.**

### A2. Real dependencies, not imitations

- Databases and brokers run as **real containers** (same image and version as production), started by the
  test and thrown away afterwards. Never an in-memory substitute (H2, embedded broker): it behaves
  differently and cannot run the real migrations.
- Share one set of containers per test run; do not start new ones per test class without a reason.
- Other services are replaced by **fakes that speak the real protocol** (a real gRPC/HTTP server on a
  random port whose behaviour the test chooses: OK, error, slow). Mock objects only inside unit/slice tests.
- Keep cheap pure logic real (e.g. the password hasher); mock what is slow or external.
- Tests need **no secret file, no fixed port and no running stack**. Test-only secrets are constants in test code.

### A3. Writing tests

- Given / When / Then; one behaviour per test; names read like a sentence
  (`billingUnavailableRollsBackAndPublishesNothing`). Group by method.
- Assert the full contract of errors: status, content type (`application/problem+json`), key fields.
- **Unique data per test** (e.g. `it-<uuid>@example.com`); no dependency on order or earlier runs.
- **Async:** poll with a timeout until the condition holds; never fixed sleeps. To prove something did
  *not* happen, watch for a fixed window and say why it is long enough.
- **Timing:** make the fake far slower than the timeout (10 s vs 1-2 s) and use a generous upper bound;
  the first call in a cold process pays warm-up costs.
- Check **no PII in logs** where a component logs.
- If a test documents surprising *current* behaviour, say so in a comment and record it as a finding in
  the phase file. Do not silently change production code from a test step.

### A4. Before committing a test change

1. Run the whole suite of the touched service, not only the new test.
2. Check the new test **really ran** (test count went up) - runners silently skip files with the wrong name.
3. Check it **fails when the code is broken**: break the protected line, run, revert.
4. Run new timing/async tests alone too: cold runs expose flakiness.
5. Container image builds do not run tests; tests are their own step (locally and in CI).

---

## B1. Java 21 / Spring Boot 4 (this repo)

| Level | Tooling | Example |
|---|---|---|
| unit | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `InOrder`) + AssertJ | `PatientServiceTest`, `JwtUtilTest` |
| web slice | `@WebMvcTest(XController.class)` + `MockMvc` + `@MockitoBean`; add `@Import(SecurityConfig.class)` when security matters | `PatientControllerTest`, `AuthControllerTest` |
| data slice | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Import(PostgresTestConfiguration.class)` | `PatientRepoTest` |
| integration | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` + fakes via `@DynamicPropertySource` | `PatientCreationFlowIntegrationTest` |
| gRPC API | `grpc.server.in-process-name` + `grpc.server.port=-1` + `InProcessChannelBuilder` | `BillingGrpcServiceTest` |
| gateway | `RANDOM_PORT` + `@AutoConfigureWebTestClient` + `StubHttpServer` (JDK HttpServer) | `GatewayJwtFilterTest` |
| E2E | `Integration-tests/` REST Assured through the gateway (`-Dgateway.url=...`) | `PatientJourneyIntegrationTest` |

- **Containers:** Testcontainers 2.x (`testcontainers-postgresql`, `-kafka`, `-junit-jupiter`), images
  `postgres:16` and `apache/kafka:4.3.2` as in compose, wired by `@ServiceConnection` in a shared
  `@TestConfiguration(proxyBeanMethods = false)` so Spring's context cache reuses them.
- **Fakes:** `FakeBillingServer` (real gRPC server on port 0, modes OK/UNAVAILABLE/SLOW), `StubHttpServer`.
- **Async / logs:** Awaitility `await().atMost(...).untilAsserted(...)`; `OutputCaptureExtension` for PII checks.
- **Naming:** classes end in `Test`/`Tests` (`*IntegrationTest` for slow ones). **Surefire ignores `*IT`.**
- **Dependencies:** `<scope>test</scope>`, versionless when the Boot BOM manages them. Boot 4 slices are
  modular: `spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-webflux-test`, `-kafka-test`,
  `-security-test`, plus `spring-boot-testcontainers`.
- **Mockito agent:** every POM has `maven-dependency-plugin` goal `properties`, an empty `<argLine></argLine>`
  property and surefire `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>`
  (self-attach fails on Java 21 in the container; `@{argLine}` leaves room for JaCoCo).
- **Dockerfiles** keep `-DskipTests`.
- **Run** (no JDK on the host):

  ```bash
  scripts/mvn-test.sh <service>                                # mvn clean test in maven:3.9.9-eclipse-temurin-21
  scripts/mvn-test.sh <service> -Dtest=ClassName               # one class
  scripts/mvn-test.sh <service> -Dtest='ClassName*#method*'    # nested classes need the trailing *

  # E2E (stack must be up: docker compose up -d --build)
  docker run --rm -v "$PWD/Integration-tests":/ws -v pm-m2-cache:/root/.m2 -w /ws \
    --add-host=host.docker.internal:host-gateway maven:3.9.9-eclipse-temurin-21 \
    mvn -B test -Dgateway.url=http://host.docker.internal:4004
  ```

  `mvn-test.sh` mounts the Docker socket and sets `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`
  so Testcontainers can start and reach sibling containers; it always runs `clean` (stale `target/`
  files once overrode `application.yml`).

## B2. The same principles in other stacks

| Concern | Java / Spring Boot | Node.js / TypeScript | Python | Go |
|---|---|---|---|---|
| Test runner | JUnit 5 | Jest, Vitest | pytest | `go test` |
| Assertions | AssertJ | `expect` | plain `assert` | testify `assert`/`require` |
| Mocks | Mockito | `jest.fn` / `vi.fn` | `unittest.mock`, pytest-mock | gomock, mockery, hand-written fakes |
| Web slice | `@WebMvcTest` + MockMvc | supertest on the app | FastAPI `TestClient` / httpx | `httptest.NewRecorder` |
| Real containers | Testcontainers | testcontainers-node | testcontainers-python | testcontainers-go |
| Async polling | Awaitility | `vi.waitFor`, polling helper | polling helper (e.g. tenacity) | `require.Eventually` |
| HTTP fake server | JDK HttpServer, WireMock | nock, msw | pytest-httpserver, respx | `httptest.NewServer` |
| E2E HTTP | REST Assured | supertest, Playwright API | httpx + pytest | `net/http` client |
| File naming | `*Test` (Surefire) | `*.test.ts` | `test_*.py` | `*_test.go` |
