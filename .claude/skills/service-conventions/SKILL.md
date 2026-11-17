---
name: service-conventions
description: Conventions for writing and structuring code in the Patient-Management Spring Boot microservices (learned in roadmap Phase 0). Use when adding or changing any service code, endpoint, DTO, exception, DB schema/migration, gRPC/Kafka contract, Dockerfile, docker-compose entry or application.yml - or when creating a new service.
---

# Service conventions (from Phase 0 - fixes & foundation)

These rules describe how code in this repo is written and structured. They were
established in `docs/implementation-plans/phase-00-fixes-and-foundation.md`.
Follow them for every change; if a rule must be broken, say why in a comment.

## 1. How changes are written (always)

- **Explain every change in the code.** Comments explain the *why*, not the obvious *what*.
  Tag them with the phase and step: `// PHASE n (step x.y): ...`.
- **Do not delete replaced code - comment it out** directly above/below the new code,
  labelled `// PHASE n (old):`. Whole files that are no longer used are commented out
  with an explanation at the top, not deleted.
- Larger explanations (a concept, a trade-off, a remaining gap) go into a block comment
  or Javadoc on the class. Be honest about gaps and name the phase that closes them.
- Work step by step; one commit per step: `feat|fix|chore|docs(phase-n): step x.y - <summary>`.
- Never write `.env`. Secrets come only from `.env` (local) / GitHub secrets (CI).

## 2. Repository & service structure

- Each service is an **independent Maven project** (own `pom.xml`, `mvnw`, `Dockerfile`);
  there is no parent POM. Java 21, **Spring Boot 4.0.x** everywhere.
- Boot 4 is modular: a feature needs its own starter
  (`spring-boot-starter-kafka`, `-flyway`, `-webclient`, `-actuator`, `-validation`).
  Libraries outside the Boot BOM (springdoc 3.0.x, grpc, protobuf) are pinned by hand.
- Package layout inside `com.pm.<service>` (see patient-service as the reference):

  | Package | Contents |
  |---|---|
  | `controller` | `@RestController` - HTTP only: validation, status codes, mapping to DTOs. No business logic. |
  | `service` | business logic, `@Transactional` boundaries |
  | `repository` / `repo` | Spring Data interfaces |
  | `model` | JPA entities |
  | `dto` (+ `dto/validators`) | request/response DTOs, validation groups |
  | `mapper` | static `toDto` / `toEntity` methods |
  | `exception` | domain exceptions + `GlobalExceptionHandler` |
  | `grpc` | gRPC clients/servers |
  | `kafka` | producers, consumers, event records, listeners |
  | `config` | `@Configuration` classes (only if `application.yml` cannot do it) |

- **Constructor injection** with `final` fields; no field `@Autowired`.
- Prefer Boot auto-configuration via `application.yml` over hand-written `@Configuration`.

## 3. API design

- Controllers return DTOs, never entities. Request DTOs use Bean Validation
  (`@NotBlank`, `@Email`, `@Size(max = <column length>)`, `@Past`, `@PastOrPresent`).
  Use **typed fields** (`LocalDate`, `UUID`), not strings parsed by hand.
- Create-only rules use a validation group (`CreatePatientValidationGroup`) and
  `@Validated({Default.class, CreateXGroup.class})`.
- Status codes: `201` create, `200` read/update, `204` delete,
  `400` invalid input, `404` not found, `409` conflict (duplicate, constraint race),
  `503` dependency unavailable. **A `500` must never be caused by client input.**
- Errors are **RFC 9457 `ProblemDetail`** (`application/problem+json`).
  `GlobalExceptionHandler` is `@RestControllerAdvice extends ResponseEntityExceptionHandler`;
  validation errors add an `errors` map (field -> message).
- Collections are **paginated**: `Pageable` + `@PageableDefault`, return `PagedModel<Dto>`,
  cap with `spring.data.web.pageable.max-page-size: 100`.
- Uniqueness: check (`existsBy...`) for a friendly message **and** keep the DB unique
  constraint; map `DataIntegrityViolationException` to 409 for the race.

## 4. Database

- **Flyway owns the schema.** Migrations in `src/main/resources/db/migration/V<n>__<what>.sql`;
  never edit an applied migration - add a new one. Hibernate runs `ddl-auto: validate`.
- `baseline-on-migrate: true`, `baseline-version: 1` (for databases created before Flyway).
- Dev/test seed data lives in a separate location (`db/dev-data/R__*.sql`) that is only
  enabled via `SPRING_FLYWAY_LOCATIONS` in docker-compose - never in production.
- `spring.sql.init.mode: never`; `show-sql` defaults to `false`.

## 5. Inter-service communication

- **Protobuf contracts live once in root `proto/`.** Each service's `protobuf-maven-plugin`
  uses `<protoSourceRoot>${project.basedir}/../proto</protoSourceRoot>` and `<includes>`
  for only the files it needs. Never change or reuse a field number; only add fields.
- **gRPC clients:** channel as a field, closed in `@PreDestroy`; every call has a deadline
  (`withDeadlineAfter`, configurable, default 3s); catch `StatusRuntimeException` and
  translate it to a domain exception (-> 503).
- **Writes to DB + another system:** do the DB work in `@Transactional`; synchronous calls
  that must succeed go inside (failure -> rollback); events go out **after commit**
  (`ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`).
  The remaining dual-write gap is solved by the outbox in Phase 4.
- **Kafka:** config in `application.yml` only; key = aggregate id (ordering per entity);
  handle the `send()` `CompletableFuture` result (`whenComplete`) and log partition/offset;
  `max.block.ms` bounded.
- **Gateway filters** (reactive): every downstream call has a timeout; downstream 4xx -> 401,
  unreachable/5xx/timeout -> 503, body as Problem Details.

## 6. Configuration, logging, operations

- `application.yml` holds non-secret defaults as `${ENV_VAR:default}`; secrets have **no**
  default and are passed by compose with `${VAR:?}` (fail fast if missing).
- **No PII in logs** (names, emails, addresses, birth dates, tokens). Log IDs only.
- Every service has Actuator with only `health,info` exposed, probes enabled,
  `show-details: never`.
- Dockerfile: multi-stage (`maven:3.9.9-eclipse-temurin-21` -> `eclipse-temurin:21-jre-alpine`),
  copy `pom.xml` first + `dependency:go-offline` for layer caching. Services that need
  `proto/` build with the **repo root** as context (`dockerfile: <svc>/Dockerfile`).
- docker-compose: every service has a readiness healthcheck
  (`wget -qO- http://localhost:<port>/actuator/health/readiness`), `restart: unless-stopped`,
  and `depends_on: condition: service_healthy`. Images are pinned to exact versions.

## 7. Verify before committing

No JDK on the host - build and test through Docker:

```bash
docker compose up --build -d            # all containers must become healthy
docker compose ps
# then exercise the changed endpoints through the gateway (localhost:4004)
# and run Integration-tests against the running stack
```

Check status codes for the happy path *and* the failure paths (bad input, duplicate,
not found, dependency down), and grep the logs for PII.
