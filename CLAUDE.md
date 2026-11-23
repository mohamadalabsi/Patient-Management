# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Spring Boot microservices (Java 21, Maven), each service is an **independent Maven project** with its own `mvnw` — there is no root/parent POM. Services: `api-gateway` (4004), `patient-service` (4000), `billing-service` (HTTP 4002, gRPC 9001), `analytics-service` (4003). `auth-service` (4005) is **retired** since Phase 3 (Auth0 replaced it): its code and tests stay in the repo and CI, but it is commented out of compose and the gateway. `Integration-tests/` is a separate Maven project (REST Assured) with no wrapper. `api-requests/` and `grpc-requests/` hold IntelliJ `.http` files for manual testing.

The former `infrastructure/` (AWS CDK / LocalStack) folder was removed intentionally (cloud returns with Terraform in roadmap phase 8). The gateway's `application-prod.yml` was written for that deployment and is currently unused.

All services are on **Spring Boot 4.0.9** (aligned in Phase 0). `api-gateway` uses Spring Cloud 2025.1.x with `spring-cloud-starter-gateway-server-webflux` (routes under `spring.cloud.gateway.server.webflux.routes`) plus `spring-boot-starter-webclient`; the others are servlet/WebMVC. Boot 4 is modular: features like Kafka, Flyway, WebClient need their own `spring-boot-starter-*` for auto-configuration. Libraries outside the Boot BOM (springdoc 3.0.x, grpc 1.69, protobuf, `net.devh` gRPC server starter in billing only) are pinned by hand.

Work is organised in phases (`docs/implementation-plans/roadmap.md`, one `phase-XX-*.md` per phase). Code convention in this repo: changes are explained in code comments tagged `PHASE n (step x.y)`, and replaced code is kept commented out as `PHASE n (old)` rather than deleted. Follow `.claude/skills/service-conventions` for all service code.

## Phase workflow (mandatory for every phase)

1. **The developer creates the phase branch** (`feature/phase-XX-<name>` from `dev-branch`). Never create, switch, delete, push or merge branches yourself.
2. **First commit of the phase = the plan**: `docs/implementation-plans/phase-XX-<name>.md` and the phase row in the roadmap Progress table set to in progress. Wait for the developer to approve the plan before writing code. The plan must be understandable **without reading the code** (`phase-01-testing.md` is the model):
   - sections: Goal · Out of scope · **Technology map** (technology, what it is, why we use it, which steps) · Versions · Concepts · Steps · Verification · Exercises · Risks · What changed;
   - every step explains **What** · **Why** · **How** (which technology, how it works, key files) · **When** (when to use the same technique elsewhere) · **Verified** (how it was tested);
   - a **"What you need to do"** section: every manual action outside the repo (accounts, tokens, secrets, GitHub/Docker Hub/Auth0/AWS settings), with where to click and before which step. Remind the developer again at that step;
   - a **"Run order - who does what"** table: the exact numbered sequence from approval to merge, alternating the developer's actions (approve, push, open/merge PR, create accounts/secrets, check external dashboards) and Claude's (implement step, verify, commit, read CI results). Claude stops at every developer line and waits;
   - **keep phases small: about 4-6 steps.** Group related work into one step instead of many tiny ones; the developer already knows some topics (e.g. CI, Docker Hub) and wants the essentials, not a tutorial.
3. **One commit per step** (`feat|fix|chore|docs(phase-N): step N.x - ...`), each verified against Docker before committing. Code comments (`PHASE n (step x.y)`) explain each change in the code too, including CI/YAML files (what every job and step does).
4. **Second-to-last commit = docs**: README, this file, roadmap Progress (done), the phase file's "What changed".
5. **Last commit of the phase = the phase skill**: create or extend a skill in `.claude/skills/<name>/SKILL.md` capturing the conventions the phase established, and add its row to the skill table in `.claude/README.md`. A phase is not finished without it. Skills are **stack-agnostic**: part A = principles that hold in any language; part B = how they map to a stack (B1 = Java/Spring Boot as used in this repo, with exact tools and commands; B2 = equivalents for other common stacks).
6. Stop and report; the developer pushes, opens the PR into `dev-branch` and creates the next branch.

(Phase 1 is the one exception: its first commit is the Phase 0 skill + `.claude/settings.json`, because Phase 0 was already merged; its plan is the second commit.)

## Commands

No JDK or Maven is installed on the host — build and test through Docker:

```bash
# Run the whole stack (requires a root .env, see README for the variable list)
docker compose up --build
docker compose up --build patient-service      # rebuild one service
docker compose down -v                         # also wipes DB volumes (needed after changing DB creds in .env)

# Test one service without a local JDK (maven image + Docker socket for Testcontainers)
scripts/mvn-test.sh patient-service
scripts/mvn-test.sh patient-service -Dtest=PatientControllerTest          # one class
scripts/mvn-test.sh patient-service -Dtest='PatientServiceTest*#create*'  # methods (nested classes need the *)

# End-to-end tests: stack WITH the fake token issuer (docker-compose.e2e.yml), then the tests
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build --wait
docker run --rm -v "$PWD/Integration-tests":/ws -v pm-m2-cache:/root/.m2 -w /ws \
  --add-host=host.docker.internal:host-gateway maven:3.9.9-eclipse-temurin-21 \
  mvn -B test -Dgateway.url=http://host.docker.internal:4004 -Dtoken.url=http://host.docker.internal:8090/default/token
```

With a local JDK, the equivalent is `./mvnw test` inside a service directory. Per-service tests need only Docker: Testcontainers starts `postgres:16` / `apache/kafka:4.3.2` (same images as compose) and `@ServiceConnection` wires them; no `.env`, no fixed ports. `scripts/mvn-test.sh` always runs `mvn clean test` (stale `target/` files once overrode `application.yml`). Mockito runs as a `-javaagent` configured in each POM's surefire plugin (self-attach fails on Java 21 in the container). Test classes must end in `Test`/`Tests` - surefire ignores `*IT`. Follow `.claude/skills/testing-conventions` when writing tests. `Integration-tests/` (REST Assured E2E) gets tokens from `mock-oauth2-server` (client credentials; `E2E.token(clientId)`); which client gets which audience/permissions is in `Integration-tests/mock-issuer/mock-oauth2-server.json`. The mock writes the request's host into `iss`, so the tests send `Host: mock-oauth2-server:8080` (the issuer the services trust in the E2E stack). Service tests never use a real issuer: the gateway signs its own test tokens (`TestTokens`), patient/billing use a table-based test `JwtDecoder` or `@WithMockUser`; `src/test/resources/application.properties` provides placeholder `AUTH0_*` values. Dockerfiles keep `-DskipTests` (Testcontainers inside `docker build` would need Docker-in-Docker); tests run as their own step.

**CI** (`.github/workflows/ci.yml`, Phase 2): `changes` (dorny/paths-filter: one filter per service, `proto/*` counts for the services that compile it, a second `stack` filter for e2e) → `test` matrix (`./mvnw -B verify` incl. JaCoCo report; `mvnw` must stay executable in git, 100755) → `e2e` (generated env file in `$RUNNER_TEMP`, `COMPOSE_PROJECT_NAME=pm-e2e`, `COMPOSE_FILE=docker-compose.yml:docker-compose.e2e.yml` so the fake issuer replaces Auth0, `docker compose up --build --wait`, `Integration-tests`) → `images` matrix (Buildx + gha cache, metadata tags `sha-<short>`/`dev`/`latest`, Trivy report + gate on fixable CRITICAL, login + push only on `push` events) → `ci-result` (the single check for branch protection). Every third-party action is pinned to a full commit SHA with the version as a comment; Dependabot (`.github/dependabot.yml`, PRs into `dev-branch`) updates them. Lint workflow changes with `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint:1.7.12` before committing. Follow `.claude/skills/ci-conventions` for CI changes. A new service needs: a filter in `changes`, a build-context case in `images`, a Dependabot directory.

Each service's Dockerfile is a multi-stage Maven build (`mvn package -DskipTests`, then the jar is split into Spring Boot layers with `-Djarmode=tools extract --layers` and copied layer by layer; start command stays `java -jar app.jar`), so `docker compose up --build` compiles from source — no need to pre-build jars. patient/billing/analytics are built with the **repo root** as build context (`dockerfile: <svc>/Dockerfile`) so they can copy `proto/`; root `.dockerignore` keeps the context small. All services have Actuator readiness healthchecks and start via `depends_on: service_healthy`.

## Architecture

**Request flow:** client → `api-gateway` → downstream service. Gateway routes are defined in `api-gateway/src/main/resources/application.yml`:
- `/api/patients/**` → patient-service (prefix stripped → `/patients/**`); the `Authorization` header is forwarded unchanged
- `/api-docs/patients` → patient-service `/v3/api-docs` (public)
- The `/auth/**` routes and the `JwtValidation` filter are commented out as `PHASE 3 (old)`.

**Auth (Phase 3, Auth0):** Auth0 is the issuer (RS256 tokens; API identifier = audience `https://api.patient-management.local`; RBAC with the `permissions` claim). Every token check is local, against the issuer's JWKS, configured by `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${AUTH0_ISSUER_URI}` (with trailing `/`) + `audiences: ${AUTH0_AUDIENCE}` — no defaults on purpose.
- **api-gateway** (reactive `SecurityWebFilterChain`, `config/SecurityConfig`): `/api/**` authenticated, health + `/api-docs/**` + CORS preflight public, rest `denyAll`; 401/403 Problem Details via `ProblemDetailsWriter`; CORS from `CORS_ALLOWED_ORIGINS`.
- **patient-service** (servlet `SecurityFilterChain` + `@EnableMethodSecurity`): `authorities-claim-name: permissions`, `authority-prefix: ""`; `@PreAuthorize("hasAuthority('read|write|delete:patients')")` on each controller method. Known: body validation (400) runs before `@PreAuthorize` (403).
- **Service-to-service:** patient-service is an OAuth2 client (`spring.security.oauth2.client.registration.billing`, client credentials, `PATIENT_SERVICE_CLIENT_ID/SECRET`, `token-uri` = `${AUTH0_TOKEN_URI:${AUTH0_ISSUER_URI}oauth/token}`); `ServiceTokenConfig` adds Auth0's `audience` parameter and caches the token (`AuthorizedClientServiceOAuth2AuthorizedClientManager`); `BearerTokenCallCredentials` puts it into gRPC metadata. billing-service checks it in `BearerTokenInterceptor` (`@GrpcGlobalServerInterceptor`, plain io.grpc + Spring `JwtDecoder`; method → permission map, deny by default, health/reflection public); its HTTP side allows only health. No token/denied → patient-service answers 503 and rolls back.
- analytics-service has no API and no security (Kafka consumer only). Follow `.claude/skills/security-conventions` for security changes.

**Patient creation fan-out** (`PatientService.createPatient`, `@Transactional`):
1. `saveAndFlush` to PatientDB (schema owned by **Flyway** `db/migration`, Hibernate `ddl-auto: validate`).
2. Synchronous gRPC call to billing-service `CreateBillingAccount` via `BillingServiceGrpcClient` with a deadline (`BILLING_SERVICE_DEADLINE`, default 3s). Failure → `BillingServiceUnavailableException` → rollback → HTTP 503.
3. Publishes an in-JVM `PatientCreatedEvent`; `PatientEventListener` (`@TransactionalEventListener(AFTER_COMMIT)`) sends the protobuf `PatientEvent` to Kafka topic `patient`, keyed by patient id. `analytics-service` consumes it and logs IDs only. Kafka config lives only in `application.yml` (Boot auto-config; the old Java config classes are commented out). Known gap until Phase 4 (outbox): event lost if the send fails after commit.

Errors in patient-service are RFC 9457 `ProblemDetail`s (`GlobalExceptionHandler extends ResponseEntityExceptionHandler`): 400 validation/malformed, 404 not found, 409 duplicate email (incl. constraint races), 503 billing down or service token refused; 401/403 are written by `SecurityConfig` in the same shape. `GET /patients` is paginated (`PagedModel`, max size 100). Logs must not contain patient PII (name/email) — log IDs only.

**Protobuf contracts live once in root `proto/`** (`billing_service.proto`, `patient_event.proto`). Each service's `protobuf-maven-plugin` uses `<protoSourceRoot>${project.basedir}/../proto</protoSourceRoot>` with `<includes>` for only the contracts it needs; the old per-service copies are commented out and no longer compiled. Never change or reuse field numbers. Java classes are generated at build time into `target/` — not checked in.

**Configuration:** each service's `application.yml` holds non-secret defaults with `${ENV:default}` placeholders; secrets (DB creds, `PATIENT_SERVICE_CLIENT_SECRET`) and the Auth0 settings (`AUTH0_ISSUER_URI`, `AUTH0_AUDIENCE`, `PATIENT_SERVICE_CLIENT_ID`) come only from the root `.env` via `docker-compose.yml`, which uses `${VAR:?}` so compose fails fast if one is missing. One Postgres container in compose: patient DB on host port 5000 (the auth DB on 5001 was retired with auth-service). Kafka (`apache/kafka:4.3.2`, KRaft, single node) is `kafka:9092` inside the network and `localhost:9094` from the host. Actuator exposes only `health`/`info` (liveness/readiness probes enabled).
