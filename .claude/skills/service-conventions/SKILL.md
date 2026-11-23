---
name: service-conventions
description: How to structure and write backend microservice code cleanly - change/comment style, service layout and layers, clean code rules, REST API design and error format, database migrations, communication between services (gRPC, HTTP, messaging) with timeouts and after-commit events, configuration and secrets, logging without personal data, health checks, container images and local orchestration. Principles for any stack (part A), the concrete Java 21 / Spring Boot microservices implementation (part B), equivalents for other stacks (part C). Project-agnostic. Use when creating a service or changing service code, an endpoint, DTO, entity, schema/migration, gRPC/event contract, Dockerfile, compose file or configuration.
---

# Service conventions

**How to use this skill:** part A holds in every language. Part B is the default implementation for
Java / Spring Boot microservices. Part C maps the same ideas to other stacks. Skip what the project
doesn't have (e.g. no messaging); if a rule must be broken, say why in a comment. Project-specific facts
(service names, ports, commands) belong in the project's own docs (README, CLAUDE.md), not here.
Related skills: `testing-conventions`, `ci-conventions`, `security-conventions`.

---

## A. Principles (any stack)

### A1. How changes are written

- **Explain changes in the code**: comments say *why* and *how the pieces connect* ("we do this, so
  that happens, and this is how it reaches the next component"), not the obvious *what*.
- If the project works in phases/steps, tag comments with them: `PHASE n (step x.y): ...`.
- **Replaced code is commented out next to the new code** (`PHASE n (old): ...`), not deleted, when
  the project wants a readable history in the code. Files no longer used get an explanation at the top.
- Bigger explanations (a concept, a trade-off, a known gap) go in a block comment on the class/module.
  Be honest about gaps and say what closes them.
- Small steps, one commit per step: `feat|fix|chore|docs(<scope>): <summary>`, each verified before
  committing.
- Never write secret files (`.env`, key files). Secrets come from the environment or a secret store.

### A2. Service structure

- Each service is **independently buildable and deployable**: own build file, own image, **own
  database**. Services never share a database; they talk through APIs or events.
- Layers, from the edge inward; each layer only calls the one below:

  | Layer | Responsibility | Must NOT |
  |---|---|---|
  | **controller / handler** | transport only: parse, validate, status codes, map to DTOs | contain business rules, touch the DB |
  | **service** | business rules, transaction boundaries | know about HTTP |
  | **repository** | data access | contain rules |
  | **model / entity** | persistent shape | leave the service (never returned by the API) |
  | **dto** | request/response shapes + validation | |
  | **mapper** | dto <-> entity conversion | |
  | **exception** | domain errors + one global error handler | |
  | **integration adapters** (gRPC/HTTP clients, producers, consumers) | talk to other systems, translate their errors into domain errors | leak transport errors upward |
  | **config** | wiring that configuration files can't express | |

- Package **by feature** once a service grows beyond one aggregate (`orders/`, `payments/`, each with
  its own layers); a small service can stay layered.

### A3. Clean code rules

- Dependencies through the **constructor**, immutable (`final`). No static state, no service locators.
- Small classes and methods with one reason to change; names say what, not how (`placeOrder`, not
  `process`). No abbreviations except well-known ones (`id`, `dto`, `url`).
- **Fail fast and loudly**: validate at the boundaries, throw domain exceptions with a clear message,
  never swallow exceptions, never return `null` for "not found" (use `Optional` or throw).
- No magic numbers/strings: named constants or configuration.
- Prefer framework configuration files over hand-written configuration code.
- Immutable value types (records) for DTOs, events and messages.
- Delete dead code paths and unused dependencies (or comment them out per A1); no commented code
  without an explanation.
- Every external call, loop over unbounded input and retry has a limit (timeout, page size, max attempts).

### A4. API design

- Return DTOs, never entities. Validate input with **typed fields** (date, UUID, enum), not hand-parsed
  strings. Create-only rules are separate from update rules.
- Status codes: `201` create (+ `Location`), `200` read/update, `204` delete, `400` invalid input,
  `401` no valid token, `403` not allowed, `404` not found, `409` conflict (duplicate, version clash),
  `503` dependency unavailable. **A `500` must never be caused by client input.**
- Errors are **RFC 9457 Problem Details** (`application/problem+json`), produced by one global handler;
  validation errors add an `errors` map (field -> message). Never leak stack traces or SQL.
- Collections are **paginated**: default size, **hard maximum** (e.g. 100), stable sort order.
  Large or infinite feeds use **cursor pagination** (`?after=<opaque cursor>`), not offsets.
- Uniqueness: check first for a friendly message **and** keep a DB unique constraint; map the constraint
  violation to `409` (the race between check and insert).
- Version the API in the path or a header when breaking changes are unavoidable; prefer additive changes.
- Document it with OpenAPI generated from the code.

### A5. Database

- **Versioned migrations own the schema.** Never edit an applied migration - add a new one. The ORM
  only *validates* the schema; it never creates or changes it.
- Seed/demo data lives in a separate migration location enabled only in development.
- Optimistic locking (version column) where concurrent updates matter.
- Indexes for every query path; no unbounded queries.

### A6. Communication between services

- **Contracts (proto / OpenAPI / event schemas) live once** in a shared place and are generated into
  each service at build time. Proto: never change or reuse a field number; only add fields.
- **Every remote call has a deadline/timeout** (configurable, ~1-3 s). Transport errors are translated
  into a domain exception -> `503`. Retries only for idempotent calls, with backoff and a limit.
- **DB write + another system:** do the DB work in a transaction; synchronous calls that must succeed
  go inside (failure -> rollback); events are published **after commit**. The remaining gap (commit ok,
  publish fails) is closed by the **transactional outbox**.
- **Events:** key = aggregate id (per-entity ordering); consumers are idempotent; check the result of
  every send; bound how long a send may block; poison messages go to a dead-letter topic.
- **Gateway:** single entry point; routes, CORS, token check, rate limits; every downstream call has a
  timeout; errors in the same Problem Details format.

### A7. Configuration, logging, operations

- Configuration files hold non-secret defaults, overridable by environment variables. Secrets have **no
  default**; startup fails fast when one is missing.
- **No personal data in logs** (names, emails, phone numbers, addresses, birth dates, tokens, passwords).
  Log IDs. Structured logs with a correlation/trace id.
- Each service exposes **liveness/readiness** health endpoints and nothing else operational publicly.
- Container images: **multi-stage** (build -> small runtime image), dependency layer cached before the
  source, non-root user, exact version tags (never `latest`).
- Local orchestration: readiness healthcheck per service, restart policy, start only after dependencies
  are healthy, one network, only the gateway published.

### A8. Verify before committing

Run the whole stack, check every container is healthy, call the changed endpoints through the gateway:
the happy path **and** the failure paths (bad input, duplicate, not found, not allowed, dependency down).
Grep the logs for personal data. Then run the tests (`testing-conventions`).

---

## B. Java 21 / Spring Boot microservices

### B1. Build and project layout

- One Maven (or Gradle) project per service, each with its own wrapper (`mvnw`), `Dockerfile` and
  `application.yml`; `spring-boot-starter-parent` with the same Boot version in every service.
- **Spring Boot 4 is modular:** every feature needs its starter for auto-configuration
  (`spring-boot-starter-webmvc`, `-validation`, `-data-jpa`, `-flyway`, `-actuator`, `-kafka`,
  `-webclient`, `-security-oauth2-resource-server`, ...). Test support is modular too (`-webmvc-test`,
  `-data-jpa-test`, ...). Libraries outside the Boot BOM (springdoc, grpc-java, protobuf) are pinned by
  hand in `<properties>`.
- Packages: `com.example.<service>` with `controller`, `service`, `repository`, `model`, `dto`
  (+ `dto/validation`), `mapper`, `exception`, `client`/`grpc`/`messaging`, `config`; or per feature.
- Comments: `// PHASE n (step x.y): ...` in Java, `<!-- -->` in POMs, `#` in YAML and Dockerfiles.

### B2. Code patterns

```java
@RestController
@RequestMapping("/orders")
class OrderController {
    private final OrderService orders;                       // constructor injection, final
    OrderController(OrderService orders) { this.orders = orders; }

    @PostMapping
    ResponseEntity<OrderResponse> create(@Validated({Default.class, OnCreate.class}) @RequestBody OrderRequest request) {
        OrderResponse created = orders.create(request);
        return ResponseEntity.created(URI.create("/orders/" + created.id())).body(created);
    }

    @GetMapping
    PagedModel<OrderResponse> list(@PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable page) {
        return new PagedModel<>(orders.list(page));          // stable JSON page format
    }
}
```

- DTOs as `record`s with Bean Validation (`@NotBlank`, `@Email`, `@Size(max = <column length>)`,
  `@Past`, `@Positive`); create-only rules via a validation group (`OnCreate`).
- Mappers: small static `toDto`/`toEntity` methods or MapStruct; never expose entities.
- `@Transactional` on service methods (read-only for queries); never on controllers.
- `GlobalExceptionHandler`: `@RestControllerAdvice extends ResponseEntityExceptionHandler` returning
  `ProblemDetail` (framework errors like malformed JSON -> 400 come for free); own handlers for domain
  exceptions; `DataIntegrityViolationException` -> 409; validation -> 400 + `errors` map.
- Pagination: `spring.data.web.pageable.max-page-size: 100`; return `PagedModel`, not `Page`.
- `Optional<T>` from repositories, `orElseThrow(() -> new OrderNotFoundException(id))` in the service.

### B3. Database

- Flyway: `src/main/resources/db/migration/V<n>__<what>.sql`; `spring.jpa.hibernate.ddl-auto: validate`;
  `spring.sql.init.mode: never`; `show-sql: false`. For an existing DB: `baseline-on-migrate: true`.
- Dev seed data in `db/dev-data/R__*.sql`, enabled only by `SPRING_FLYWAY_LOCATIONS` in the dev
  environment.
- `@Version` for optimistic locking; UUID ids generated by the DB or the app, never by the client.

### B4. Communication

- **gRPC client:** `ManagedChannel` created once (field), closed in `@PreDestroy`; `withDeadlineAfter(...)`
  on **every** call; catch `StatusRuntimeException` -> domain exception (-> 503). Server: a gRPC Spring
  starter (Spring gRPC, or `net.devh` grpc-server-spring-boot-starter), services as `@GrpcService`.
- **Protobuf contracts** in one shared folder, compiled by `protobuf-maven-plugin` with
  `<protoSourceRoot>` pointing there and `<includes>` for the needed files; generated code in `target/`.
- **HTTP clients:** `RestClient`/`WebClient` with connect + read timeouts (or HTTP interface clients).
- **After-commit events:** `@Transactional` service publishes an in-JVM event via
  `ApplicationEventPublisher`; a `@TransactionalEventListener(phase = AFTER_COMMIT)` sends it to the broker.
  Next level: outbox table written in the same transaction + a relay.
- **Kafka:** configuration only in `application.yml` (serializers, `max.block.ms`, idempotent producer);
  `kafkaTemplate.send(topic, aggregateId, payload).whenComplete(...)` logging partition/offset;
  consumers with `DefaultErrorHandler` + backoff + dead-letter topic.
- **Resilience:** Resilience4j (timeout, retry for idempotent calls, circuit breaker) around remote calls.

### B5. Configuration and operations

- `application.yml` with `${ENV_VAR:default}` for non-secrets and `${ENV_VAR}` (no default) for secrets;
  the orchestrator passes them (`${VAR:?message}` in compose fails fast).
- `@ConfigurationProperties` records for groups of settings (validated with `@Validated`).
- Actuator: `management.endpoints.web.exposure.include: health,info`,
  `management.endpoint.health.probes.enabled: true`, `show-details: never`.
- Logging: SLF4J with placeholders, IDs only; JSON logs + trace ids (Micrometer Tracing) in production.
- Dockerfile:

  ```dockerfile
  FROM maven:3.9-eclipse-temurin-21 AS build
  WORKDIR /workspace
  COPY pom.xml .
  RUN mvn -B dependency:go-offline          # cached until pom.xml changes
  COPY src src
  RUN mvn -B package -DskipTests \
   && java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted --application-filename app.jar

  FROM eclipse-temurin:21-jre-alpine
  RUN addgroup -S spring && adduser -S spring -G spring
  USER spring
  WORKDIR /app
  COPY --from=build /workspace/extracted/dependencies/ ./
  COPY --from=build /workspace/extracted/spring-boot-loader/ ./
  COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
  COPY --from=build /workspace/extracted/application/ ./
  ENTRYPOINT ["java", "-jar", "app.jar"]
  ```

  Tests don't run inside `docker build` (`-DskipTests`): container-based tests would need Docker-in-Docker.
  If services share files outside their folder (contracts), build them with the repo root as context.
- docker-compose: `healthcheck` on `/actuator/health/readiness` (`wget -qO-` on Alpine),
  `restart: unless-stopped`, `depends_on: {db: {condition: service_healthy}}`, exact image tags for
  databases and brokers, named volumes, only the gateway port published.

---

## C. The same principles in other stacks

| Concern | Java / Spring Boot | Node.js / TypeScript | Python | Go |
|---|---|---|---|---|
| Web framework | Spring Web MVC / WebFlux | NestJS, Fastify, Express | FastAPI, Django REST | net/http, chi, gin |
| Input validation | Bean Validation | class-validator, zod | Pydantic | go-playground/validator |
| Problem Details | `ProblemDetail` | middleware / http-problem-details | exception handler | custom handler |
| DI | constructor injection | NestJS providers | FastAPI `Depends` | explicit constructors |
| Migrations | Flyway, Liquibase | Prisma Migrate, Knex | Alembic, Django migrations | golang-migrate, goose |
| Data access | Spring Data JPA, jOOQ | Prisma, TypeORM, Drizzle | SQLAlchemy | sqlc, GORM |
| gRPC | grpc-java | @grpc/grpc-js | grpcio | grpc-go |
| Kafka | spring-kafka | kafkajs | confluent-kafka | franz-go |
| Resilience | Resilience4j | cockatiel, p-retry | tenacity | failsafe-go |
| Health | Actuator | @nestjs/terminus | custom routes | custom handlers |
| Runtime image | eclipse-temurin JRE | node:<ver>-alpine | python:<ver>-slim | distroless / scratch |
