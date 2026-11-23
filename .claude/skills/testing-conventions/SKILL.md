---
name: testing-conventions
description: How tests are chosen, written, structured and run for backend microservices - test levels, real databases and brokers in containers, fakes for other services, security tests, async and timing tests, naming, proving a test can fail, pre-commit checks. Principles for any stack (part A), the concrete Java 21 / Spring Boot implementation with JUnit, Mockito, AssertJ, Spring test slices, Testcontainers, Awaitility and REST Assured (part B), equivalents for other stacks (part C). Project-agnostic. Use when adding or changing a test, when production code changes need a test, when a test fails or is flaky, or when touching test dependencies, container setup, test runner configuration or end-to-end tests.
---

# Testing conventions

**How to use this skill:** part A holds in every language. Part B is the default implementation for
Java / Spring Boot microservices. Part C maps it to other stacks. Project-specific commands (how to run
the suite here, which services exist) belong in the project's docs. Code style (explaining comments,
small commits) comes from `service-conventions`; tests that need tokens: `security-conventions`.

---

## A. Principles (any stack)

### A1. Pick the lowest level that can prove the behaviour

| Change | Level | Starts | Proves |
|---|---|---|---|
| business rule, mapper, util | **unit** | one class, collaborators mocked | logic, in milliseconds |
| endpoint, status code, validation, error body, pagination, permissions | **web slice** | HTTP layer (+ security) only, service mocked | the API contract |
| query, constraint, migration | **data slice** | data layer + real database | the SQL really behaves so |
| transactions, events, calls to other services, timeouts | **integration** | whole service + real infra + fakes | the pieces work together |
| an API other services depend on (gRPC/HTTP) | **API test** | the server, called by a real client | the contract |
| a user journey across services | **end-to-end** (few!) | the whole deployed stack | it works for a client |

Most tests are unit and slice tests; go higher only for what the lower level can't see.
**Every bug fix gets a test that fails without the fix.**

### A2. Real dependencies, not imitations

- Databases and brokers run as **real containers** (same image and version as production), started by
  the test and thrown away afterwards. No in-memory substitutes (H2, embedded brokers): they behave
  differently and can't run the real migrations.
- Share one set of containers per test run (reuse the test context), not one per test class.
- Other services are replaced by **fakes that speak the real protocol**: a real HTTP/gRPC server on a
  random port whose behaviour the test chooses (OK, error, slow) and which **records what it received**.
  Mock objects only inside unit/slice tests.
- The identity provider is replaced too: test tokens signed with a test key, or a fake issuer container
  (see `security-conventions`). Tests never call the real provider.
- Keep cheap pure logic real; mock what is slow or external.
- Tests need **no secret file, no fixed port and no running stack**. Test-only values are constants in
  test code or a test configuration file.

### A3. Writing tests

- Given / When / Then; one behaviour per test; names read like a sentence
  (`paymentDownRollsBackTheOrderAndPublishesNothing`). Group by method or feature.
- Assert the full error contract: status, content type (`application/problem+json`), key fields.
- **Security per endpoint:** no token -> 401, valid token without permission -> 403, right permission ->
  success. Plus per-object rules (someone else's resource -> 403/404).
- **Unique data per test** (e.g. `user-<uuid>@example.com`); no dependency on order or earlier runs.
- **Async:** poll with a timeout until the condition holds; never fixed sleeps. To prove something did
  *not* happen, watch for a fixed window and say why it's long enough.
- **Timing:** the fake is far slower than the timeout (10 s vs 1-2 s); generous upper bounds - a cold
  process pays warm-up costs.
- Check **no personal data in logs** where a component logs.
- A test that documents surprising *current* behaviour says so in a comment and is recorded as a finding;
  don't silently change production code from a test-only change.

### A4. Before committing a test change

1. Run the whole suite of the touched service, not only the new test.
2. Check the new test **really ran** (count went up) - runners silently skip wrongly named files.
3. Check it **fails when the code is broken**: break the protected line, run, revert.
4. Run new timing/async tests alone as well: cold runs expose flakiness.
5. Image builds don't run tests; tests are their own step (locally and in CI).

---

## B. Java 21 / Spring Boot

### B1. Tooling per level

| Level | Tooling |
|---|---|
| unit | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `ArgumentCaptor`, `InOrder`) + AssertJ |
| web slice | `@WebMvcTest(XController.class)` + `MockMvc` + `@MockitoBean` for the service; `@Import(SecurityConfig.class)` (slices don't load your own `@Configuration`) |
| data slice | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + a Postgres Testcontainers config |
| integration | `@SpringBootTest` (+ `@AutoConfigureMockMvc`) + `@Import(TestcontainersConfiguration.class)`; fakes wired with `@DynamicPropertySource` |
| gRPC API | in-process server (`grpc.server.in-process-name=...`, `grpc.server.port=-1`) + `InProcessChannelBuilder` |
| reactive gateway | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureWebTestClient` + stub HTTP servers |
| end-to-end | separate module with REST Assured against the running stack (`-Dgateway.url=...`), only through the gateway |

### B2. Containers

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    @Bean @ServiceConnection
    PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:16"); }   // same tag as production
    @Bean @ServiceConnection
    KafkaContainer kafka() { return new KafkaContainer("apache/kafka:<same version>"); }
}
```

- Testcontainers 2.x modules (`testcontainers-postgresql`, `-kafka`, `-junit-jupiter`) +
  `spring-boot-testcontainers`; `@ServiceConnection` sets the connection properties; Spring's test
  context cache reuses the containers across classes with the same configuration.
- Tests running inside a container (CI or a Docker-based script) need the Docker socket and, on Docker
  Desktop, `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`.

### B3. Fakes and helpers

- **gRPC fake:** `ServerBuilder.forPort(0).addService(...)` with modes OK / UNAVAILABLE / SLOW, records
  requests and metadata (via a `ServerInterceptor`).
- **HTTP fake:** JDK `com.sun.net.httpserver.HttpServer` on port 0 (no dependency), or WireMock.
- **Security:** `@WithMockUser(authorities = {...})` for non-security tests; a `@TestConfiguration`
  `JwtDecoder` (token string -> claims table) to run the real claim -> authority mapping; self-signed
  JWTs (Nimbus `RSAKeyGenerator`, JWKS served by a stub) for gateway/filter tests; `spring-security-test`
  `jwt()` / `mockJwt()` post-processors for quick cases.
- **Async:** Awaitility `await().atMost(Duration.ofSeconds(10)).untilAsserted(...)`.
- **Logs:** `@ExtendWith(OutputCaptureExtension.class)` + `CapturedOutput` to assert "no email in logs".
- Test-only properties: `src/test/resources/application.properties` (a different file name than the
  main `application.yml`, so both load) for placeholders like issuer URLs and client ids.

### B4. Maven setup

- Test dependencies `<scope>test</scope>`, versionless when the Boot BOM manages them; Boot 4 test
  starters are modular (`spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-webflux-test`,
  `-kafka-test`, `-security-oauth2-resource-server-test`).
- **Naming:** classes end in `Test`/`Tests` (`*IntegrationTest` for slow ones). Surefire ignores `*IT`
  unless Failsafe is configured.
- **Mockito as a Java agent** (self-attach is deprecated on Java 21 and fails in some containers):
  `maven-dependency-plugin` goal `properties`, an empty `<argLine></argLine>` property, and surefire
  `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>` (`@{argLine}` keeps room
  for JaCoCo).
- Always `mvn clean test` in scripts: stale files in `target/` can override current resources.
- Run one class / method: `-Dtest=ClassName`, `-Dtest='ClassName*#method*'` (nested classes need `*`).

---

## C. The same principles in other stacks

| Concern | Java / Spring Boot | Node.js / TypeScript | Python | Go |
|---|---|---|---|---|
| Runner | JUnit 5 | Vitest, Jest | pytest | `go test` |
| Assertions | AssertJ | `expect` | `assert` | testify |
| Mocks | Mockito | `vi.fn` / `jest.fn` | `unittest.mock`, pytest-mock | gomock, hand-written fakes |
| Web slice | `@WebMvcTest` + MockMvc | supertest | FastAPI `TestClient` / httpx | `httptest.NewRecorder` |
| Real containers | Testcontainers | testcontainers-node | testcontainers-python | testcontainers-go |
| Async polling | Awaitility | `vi.waitFor` | tenacity / polling helper | `require.Eventually` |
| HTTP fake | JDK HttpServer, WireMock | msw, nock | pytest-httpserver, respx | `httptest.NewServer` |
| Test JWTs | Nimbus JOSE | `jose` | PyJWT / jwcrypto | lestrrat-go/jwx |
| E2E HTTP | REST Assured | supertest, Playwright API | httpx + pytest | `net/http` |
| File naming | `*Test` | `*.test.ts` | `test_*.py` | `*_test.go` |
