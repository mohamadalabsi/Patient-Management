---
name: service-conventions
description: How to structure and write backend microservice code - change/comment style, service layout and layers, API design, database migrations, inter-service communication (gRPC, messaging), configuration and secrets, logging, containers. Stack-agnostic principles (part A) plus the concrete Java 21 / Spring Boot 4 implementation used in this repo (part B1) and equivalents for other stacks (part B2). Use when creating a service or changing any service code, endpoint, DTO, schema/migration, gRPC/Kafka contract, Dockerfile, compose entry or configuration.
---

# Service conventions (established in roadmap Phase 0)

**How to use this skill:** part A holds in any language. Then use the stack section that matches
the project: B1 for Java/Spring Boot (this repo), B2 for the equivalent tools in other stacks.
If a rule must be broken, say why in a comment. Background: `docs/implementation-plans/phase-00-fixes-and-foundation.md`.

---

## A. Principles (any stack)

### A1. How changes are written

- **Explain every change in the code**, in the language's comment syntax. Comments explain *why*,
  not the obvious *what*. Tag them with phase and step: `PHASE n (step x.y): ...`.
- **Do not delete replaced code - comment it out** next to the new code, labelled `PHASE n (old):`.
  Files no longer used are commented out with an explanation at the top.
- Bigger explanations (a concept, a trade-off, a known gap) go in a block comment on the class/module.
  Be honest about gaps and name the phase that closes them.
- One commit per step: `feat|fix|chore|docs(phase-n): step x.y - <summary>`.
- Never write secret files (`.env`). Secrets come from the environment / CI secret store.

### A2. Service structure

- Each service is **independently buildable and deployable**: own build file, own Dockerfile, own database.
  Services never share a database; they talk through APIs or messages.
- Layers, from the edge inward - each layer only calls the one below:

  | Layer | Responsibility | Must NOT |
  |---|---|---|
  | **controller / handler** | HTTP/gRPC only: parse, validate, status codes, map to DTOs | contain business rules, touch the DB |
  | **service** | business rules, transaction boundaries | know about HTTP |
  | **repository** | data access | contain rules |
  | **model / entity** | persistent shape | leave the service (never returned from the API) |
  | **dto** | request/response shapes (+ validation) | |
  | **mapper** | dto <-> entity conversion | |
  | **exception** | domain errors + one global error handler | |
  | **integration adapters** (`grpc`, `kafka`, clients) | talk to other systems, translate their errors into domain errors | leak transport errors upward |
  | **config** | wiring that configuration files cannot express | |

- Dependencies are injected through the **constructor** and are immutable.
- Prefer framework configuration files over hand-written configuration code.

### A3. API design

- Return DTOs, never persistence entities. Validate input at the edge with **typed fields** (date, UUID),
  not strings parsed by hand. Create-only rules are separate from update rules.
- Status codes: `201` create, `200` read/update, `204` delete, `400` invalid input, `404` not found,
  `409` conflict (duplicate, constraint race), `503` dependency unavailable.
  **A `500` must never be caused by client input.**
- Errors are **RFC 9457 Problem Details** (`application/problem+json`); validation errors add an
  `errors` map (field -> message). One global handler produces them.
- Collections are **paginated** with a default and a hard maximum page size (100).
- Uniqueness: check first for a friendly message **and** keep a DB unique constraint; map the
  constraint violation to 409 for the race between check and insert.

### A4. Database

- **Versioned migrations own the schema.** Never edit an applied migration - add a new one.
  The ORM only *validates* the schema; it never creates or changes it.
- Seed/test data lives in a separate migration location enabled only in dev, never in production.

### A5. Communication between services

- **Contracts (proto/OpenAPI/event schemas) live once**, in a shared folder, and are generated into
  each service at build time. Never change or reuse a field number; only add fields.
- **Every remote call has a deadline/timeout** (configurable, default ~3 s). Transport errors are
  translated into a domain exception -> 503.
- **DB write + another system:** do the DB work in a transaction; synchronous calls that must succeed
  go inside (failure -> rollback); events are published **after commit**. The remaining gap
  (commit ok, publish fails) is closed by the **outbox pattern** (Phase 4).
- **Messages:** key = aggregate id (keeps per-entity order); check the result of every send and log
  partition/offset; bound how long a send may block.
- **Gateways:** every downstream call has a timeout; downstream 4xx -> 401, unreachable/5xx/timeout -> 503,
  body as Problem Details.

### A6. Configuration, logging, operations

- Configuration files hold non-secret defaults, overridable by env vars. Secrets have **no default** and
  start-up fails fast when one is missing.
- **No PII in logs** (names, emails, addresses, birth dates, tokens). Log IDs only.
- Each service exposes **health endpoints** (liveness/readiness) and nothing else operational by default.
- Container images: **multi-stage** (build image -> small runtime image), dependency layer cached
  before the source, exact version tags, never `latest`.
- Compose/orchestrator: readiness healthcheck per service, restart policy, start only after
  dependencies are healthy.

### A7. Verify before committing

Run the whole stack, check every container is healthy, exercise the changed endpoints through the
gateway: the happy path **and** the failure paths (bad input, duplicate, not found, dependency down).
Grep the logs for PII. Then run the tests (see `testing-conventions`).

---

## B1. Java 21 / Spring Boot 4 (this repo)

- **Build:** each service is an independent Maven project (own `pom.xml`, `mvnw`, `Dockerfile`), no parent POM.
  Boot 4 is modular: each feature needs its starter (`spring-boot-starter-kafka`, `-flyway`, `-webclient`,
  `-actuator`, `-validation`). Libraries outside the Boot BOM (springdoc 3.0.x, grpc 1.69, protobuf,
  `net.devh` gRPC server) are pinned by hand.
- **Comments:** `// PHASE n (step x.y): ...`, `// PHASE n (old): ...`, `<!-- ... -->` in POMs, `#` in YAML.
- **Packages** in `com.pm.<service>`: `controller`, `service`, `repository`/`repo`, `model`, `dto`
  (+ `dto/validators`), `mapper` (static `toDto`/`toEntity`), `exception`, `grpc`, `kafka`, `config`.
  Reference service: patient-service.
- **Injection:** constructor with `final` fields; no field `@Autowired`.
- **API:** Bean Validation on DTOs (`@NotBlank`, `@Email`, `@Size(max = <column length>)`, `@Past`,
  `@PastOrPresent`); create-only rules via a validation group and `@Validated({Default.class, CreateXGroup.class})`.
  `GlobalExceptionHandler` = `@RestControllerAdvice extends ResponseEntityExceptionHandler` returning
  `ProblemDetail`. Pagination: `Pageable` + `@PageableDefault`, return `PagedModel<Dto>`,
  `spring.data.web.pageable.max-page-size: 100`. Race: `DataIntegrityViolationException` -> 409.
- **Database:** Flyway, `src/main/resources/db/migration/V<n>__<what>.sql`; `ddl-auto: validate`;
  `baseline-on-migrate: true`, `baseline-version: 1`; dev seed in `db/dev-data/R__*.sql`, enabled only via
  `SPRING_FLYWAY_LOCATIONS` in docker-compose; `spring.sql.init.mode: never`; `show-sql: false`.
- **Contracts:** root `proto/`; `protobuf-maven-plugin` with
  `<protoSourceRoot>${project.basedir}/../proto</protoSourceRoot>` and `<includes>` for the files needed.
- **gRPC client:** channel as a field, closed in `@PreDestroy`; `withDeadlineAfter(...)` on every call;
  catch `StatusRuntimeException` -> domain exception.
- **After-commit events:** `@Transactional` service + `ApplicationEventPublisher` +
  `@TransactionalEventListener(phase = AFTER_COMMIT)` that sends to Kafka.
- **Kafka:** config only in `application.yml`; `send(topic, id, bytes).whenComplete(...)`; bounded `max.block.ms`.
- **Config:** `application.yml` with `${ENV_VAR:default}`; secrets without default, passed by compose as `${VAR:?}`.
- **Ops:** Actuator exposes only `health,info`, probes enabled, `show-details: never`.
  Dockerfile `maven:3.9.9-eclipse-temurin-21` -> `eclipse-temurin:21-jre-alpine`, `pom.xml` copied first +
  `dependency:go-offline`. Services that need `proto/` build with the repo root as context.
  Compose healthcheck: `wget -qO- http://localhost:<port>/actuator/health/readiness`, `restart: unless-stopped`,
  `depends_on: condition: service_healthy`.
- **Verify:** no JDK on the host - `docker compose up --build -d`, `docker compose ps`, call the gateway on
  `localhost:4004`.

## B2. The same principles in other stacks

Pick the equivalent tool; the principles in part A do not change.

| Concern | Java / Spring Boot | Node.js / TypeScript | Python | Go |
|---|---|---|---|---|
| Web framework | Spring Web MVC / WebFlux | NestJS, Express, Fastify | FastAPI, Django REST | net/http, chi, gin |
| Input validation | Bean Validation | class-validator, zod | Pydantic | go-playground/validator |
| Problem Details | `ProblemDetail` | small middleware / http-problem-details | custom exception handler | custom handler |
| DI | Spring constructor injection | NestJS providers | FastAPI `Depends` | explicit constructors |
| Migrations | Flyway, Liquibase | Prisma Migrate, Knex, TypeORM migrations | Alembic, Django migrations | golang-migrate, goose |
| ORM / data access | Spring Data JPA | Prisma, TypeORM | SQLAlchemy | sqlc, GORM |
| gRPC | grpc-java | @grpc/grpc-js | grpcio | grpc-go |
| Kafka | spring-kafka | kafkajs | confluent-kafka | franz-go, confluent-kafka-go |
| Health endpoints | Actuator | @nestjs/terminus | custom `/health` routes | custom handlers |
| Runtime image | eclipse-temurin JRE | node:<ver>-alpine | python:<ver>-slim | distroless / scratch |
