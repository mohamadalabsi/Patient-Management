---
name: testing-conventions
description: How tests are written, structured and run in the Patient-Management microservices (established in roadmap Phase 1). Use when adding or changing any test, when changing production code that needs a test, when a test fails or is flaky, or when touching test dependencies, Testcontainers setup, surefire config or the Integration-tests (REST Assured) module.
---

# Testing conventions (from Phase 1 - testing safety net)

Established in `docs/implementation-plans/phase-01-testing.md`. Code style rules
(`PHASE n` comments, old code commented out, one commit per step) come from the
`service-conventions` skill and apply to tests too.

## 1. Which test for which change

Pick the LOWEST level that can prove the behaviour; go higher only for what the lower level cannot see.

| Change | Test type | Tooling | Example |
|---|---|---|---|
| business rule in a `@Service`, mapper, util | **unit** (no Spring) | JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`) + AssertJ | `PatientServiceTest`, `JwtUtilTest` |
| endpoint, status code, validation, error body, pagination | **web slice** | `@WebMvcTest(XController.class)` + `MockMvc` + `@MockitoBean` service | `PatientControllerTest` |
| repository query, DB constraint, migration | **JPA slice** | `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Import(PostgresTestConfiguration.class)` | `PatientRepoTest` |
| transactions, events after commit, gRPC/Kafka wiring, timeouts | **integration** | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` + fakes for other services | `PatientCreationFlowIntegrationTest` |
| gRPC server API | in-process gRPC | `grpc.server.in-process-name` + `InProcessChannelBuilder` | `BillingGrpcServiceTest` |
| gateway filters / routes | gateway on `RANDOM_PORT` | `@AutoConfigureWebTestClient` + `StubHttpServer` (JDK HttpServer) | `GatewayJwtFilterTest` |
| a user journey across services | **E2E** (few!) | `Integration-tests/` REST Assured through the gateway | `PatientJourneyIntegrationTest` |

Every bug fix gets a test that fails without the fix.

## 2. Real dependencies, not imitations

- **Postgres and Kafka come from Testcontainers**, same images as `docker-compose.yml`
  (`postgres:16`, `apache/kafka:4.3.2`), wired by `@ServiceConnection`. Never H2, never embedded Kafka:
  they behave differently and do not run our Flyway migrations unchanged.
- Container beans live in a shared `@TestConfiguration(proxyBeanMethods = false)` per service
  (`TestcontainersConfiguration`, `PostgresTestConfiguration`). Reuse them so Spring's context cache
  shares ONE set of containers. Do not create a unique config per test class without a reason.
- Other services are replaced by **fakes that speak the real protocol**: `FakeBillingServer`
  (real gRPC server on port 0), `StubHttpServer` (real HTTP). Configure their URLs with
  `@DynamicPropertySource`. Mock with Mockito only inside unit/slice tests.
- Keep cheap pure logic real (e.g. real `BCryptPasswordEncoder`); mock what is slow or external.
- No `.env`, no fixed ports, no running compose stack. Test-only secrets are constants in test code
  (e.g. `TestcontainersConfiguration.TEST_JWT_SECRET`).

## 3. Writing tests

- Given / When / Then structure; one behaviour per test; method names read like a sentence
  (`billingUnavailableRollsBackAndPublishesNothing`). Group by method with `@Nested`.
- Assertions with AssertJ (`assertThat`, `assertThatThrownBy`); `jsonPath` for HTTP bodies; assert the
  `application/problem+json` content type for errors.
- **Unique data per test** (`"it-" + UUID.randomUUID() + "@example.com"`). Tests must not depend on order
  or on data from other tests or earlier runs.
- **Async (Kafka, listeners):** Awaitility (`await().atMost(...).untilAsserted(...)`), never `Thread.sleep`.
  To prove something did NOT happen, observe for a fixed window and say why the window is long enough.
- **Timing assertions:** make the fake much slower than the timeout (e.g. 10 s vs 1-2 s) and use a
  generous upper bound - the first call in a cold JVM pays warm-up costs.
- Check "no PII in logs" where a component logs (`OutputCaptureExtension`).
- When a test documents surprising CURRENT behaviour (not the desired one), say so in a comment
  and record it as a finding in the phase file. Do not silently change production code from a test step.

## 4. Naming and Maven setup

- Test classes MUST end in `Test` or `Tests` (`*IntegrationTest` for slow ones). Surefire ignores `*IT`.
- Test dependencies are `<scope>test</scope>` and versionless when the Boot BOM manages them.
  Boot 4 slices are modular: `spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-webflux-test`,
  `-kafka-test`, `-security-test`, plus `spring-boot-testcontainers` and `testcontainers-<module>` (TC 2.x names).
- Every POM loads Mockito as an agent: `maven-dependency-plugin` goal `properties` +
  surefire `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>` and an empty
  `<argLine></argLine>` property. Keep `@{argLine}` so coverage agents (JaCoCo) can be added.
- Dockerfiles keep `-DskipTests`; tests run as their own step.

## 5. Running tests

```bash
scripts/mvn-test.sh <service>                                  # mvn clean test in maven:3.9.9-eclipse-temurin-21
scripts/mvn-test.sh <service> -Dtest=ClassName                 # one class
scripts/mvn-test.sh <service> -Dtest='ClassName*#method*'      # nested classes need the trailing *

# E2E (stack must be up: docker compose up -d --build)
docker run --rm -v "$PWD/Integration-tests":/ws -v pm-m2-cache:/root/.m2 -w /ws \
  --add-host=host.docker.internal:host-gateway maven:3.9.9-eclipse-temurin-21 \
  mvn -B test -Dgateway.url=http://host.docker.internal:4004
```

`mvn-test.sh` mounts the Docker socket and sets `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`
so Testcontainers can start and reach sibling containers. It always runs `clean`.

## 6. Before committing a test change

1. Run the whole suite of the touched service (not only the new class) - context sharing and test
   order can change results.
2. Check the new test really runs (count in `Tests run:`), and that it **fails when the code is broken**:
   break the production line it protects, run, revert (`git checkout -- <file>`).
3. Run a new or changed timing/async test alone too (`-Dtest=...`): cold-JVM runs expose flakiness.
