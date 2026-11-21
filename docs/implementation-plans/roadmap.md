# Patient-Management — Audit & Implementation Roadmap

> Status: **approved** (2026-11-13). Baseline: `dev-branch` @ `d669b5d`.
> Identity provider decision: **Auth0** (chosen over Keycloak, Cognito and an own token engine: hosted OIDC, matches industry practice; services stay provider-neutral via `issuer-uri`).
>
> Working agreement:
> - One phase at a time. Before a phase starts, the owner creates a new branch for it; the phase is merged back via PR.
> - Each phase is planned in Plan Mode first and gets its own file in this folder (`phase-XX-*.md`) **when the phase starts**, not up front.
> - Every phase file uses the same sections: *Goal · Concepts · Steps · Verification · Exercises · What changed*.
> - Explain the *why* of each decision; don't rewrite working code without a reason.

---

## 1. Repository overview

| Item | State at baseline |
|---|---|
| Build | 5 independent Maven projects + `Integration-tests`; no parent POM |
| Java / Boot | Java 21. `api-gateway` on **Boot 3.4.1** (Spring Cloud 2024.0.0); `auth`, `patient`, `billing`, `analytics` on **Boot 4.0.1–4.0.2** |
| Branches | `dev-branch` holds all recent work. `master` and `staging-branch` still point at `7d0fe72` (PR #1 was merged into `dev-branch`, not `master`) |
| CI/CD | none (no `.github/`) |
| Monitoring | none (no Actuator, metrics, tracing) |
| Infrastructure | old `infrastructure/` (CDK/LocalStack) deliberately removed; README AWS sections are outdated |
| Hygiene | `Integration-tests/target/*.class` committed; 13 `.idea/` files tracked; `.gitignore` only contains `.env` |

## 2. Existing architecture

```
Client ─► api-gateway :4004 (Spring Cloud Gateway, reactive)
            │  /auth/**          → auth-service :4005 ──► AuthDB (Postgres, host 5001)
            │  /api/patients/**  → JwtValidation filter ──HTTP GET /validate──► auth-service
            │                     → patient-service :4000 ──► PatientDB (Postgres, host 5000)
            │                            ├─ gRPC (sync) ─► billing-service :9001 (stub)
            │                            └─ Kafka topic "patient" (protobuf bytes) ─► analytics-service (logs only)
```

- **Auth today:** auth-service issues HS256 JWTs (jjwt, shared secret, 10 h lifetime). The gateway does not verify tokens itself; it calls `/validate` on every request. Downstream services have no security.
- **Patient creation:** save to DB → synchronous gRPC `CreateBillingAccount` → publish `PatientEvent` to Kafka.
- **Contracts:** `.proto` files are duplicated per service (plus a stray copy in `analytics-service/src/main/`).
- **Config:** `application.yml` with `${ENV:default}`; secrets only from root `.env` via compose `${VAR:?}`.

## 3. Strengths to preserve

- Clear service boundaries, database-per-service.
- Multi-stage Dockerfiles running as a non-root user.
- Compose fails fast on missing secrets; Postgres healthchecks with `service_healthy`.
- patient-service not exposed to the host — only reachable via the gateway.
- gRPC + Protobuf and Kafka + Protobuf both used correctly.
- DTO / mapper / entity separation; validation groups for create vs update.
- BCrypt password hashing; modern jjwt API.
- An end-to-end REST Assured test suite exists.

## 4. Audit findings

Tags: **Confirmed** (traced in code) · **Likely** (needs one test to prove) · **Risk** (works today, fragile).

### 4.1 Bugs

| # | Finding | Location | Tag |
|---|---|---|---|
| B1 | Dual-write: patient is saved, then billing gRPC fails → client gets 500, patient exists without billing account or event; retry fails with "email exists" | `PatientService.createPatient` | Confirmed |
| B2 | `kafkaTemplate.send()` is async — the `try/catch` never sees broker failures; messages have no key (no per-patient ordering) | `KafkaProducer.sendEvent` | Confirmed |
| B3 | gRPC call has no deadline (a hung billing blocks the request thread); channel never shut down | `BillingServiceGrpcClient` | Confirmed |
| B4 | Not-found → 400 (should be 404); duplicate email → 400 (should be 409); invalid date → `DateTimeParseException` → 500 | `GlobalExceptionHandler`, `PatientMapper` | Confirmed |
| B5 | Email field has no `@Email`, only a meaningless `@Size(message="Email should be valid")` | `PatientRequestDTO` | Confirmed |
| B6 | auth-service has no `spring-boot-starter-validation` → `@Valid` on login is a no-op | `auth-service/pom.xml` | Confirmed |
| B7 | `contextLoads` tests need a real Postgres (no password default) and patient needs `spring.kafka.bootstrap-servers` → tests cannot run in isolation (why Dockerfiles use `-DskipTests`) | `*ApplicationTests` | Confirmed |
| B8 | `@RestControllerAdvice` does not apply to gateway route filters → invalid token likely yields 500, not 401; auth-service down yields 500, not 503 | `api-gateway/.../JwtValidationException` | Likely |
| B9 | `body("patients", notNullValue())` on a JSON array root always passes | `PatientIntegrationTest` | Confirmed |
| B10 | `get-patients.http` blank line moves `Authorization` into the body; `create-patient.http` sends no token | `api-requests/` | Confirmed |
| B11 | Check-then-insert on email races → unique-constraint violation → 500 | `PatientService` | Confirmed (minor) |

### 4.2 Security weaknesses

| # | Finding |
|---|---|
| S1 | Authentication only, no authorization: `role` claim is never enforced; any logged-in user can CRUD all patients |
| S2 | Downstream services trust everything; the gateway does not forward identity |
| S3 | HS256 shared secret — anyone who can verify can also mint tokens |
| S4 | 10-hour tokens, no refresh, no revocation |
| S5 | `data.sql` seeds an ADMIN with a password published in the repo, in every environment (`sql.init.mode: always`) |
| S6 | No rate limit on `/auth/login`; missing user skips BCrypt → timing-based user enumeration |
| S7 | PII (name, email) logged by producer, consumer, billing and gRPC client; `show-sql: true` default in patient |
| S8 | Plaintext gRPC and Kafka; billing, Postgres and Kafka ports published to host (acceptable locally only) |
| S9 | No CORS policy, security headers or request-size limits on the gateway; API docs public |

### 4.3 Architectural weaknesses

| # | Finding |
|---|---|
| A1 | Version misalignment: gateway Boot 3.4 (OSS support ended) vs Boot 4 elsewhere; Boot 4 services pin Boot-3-era libs: `spring-kafka 3.3.0`, `springdoc 2.x`, `net.devh grpc-spring-boot-starter 3.1.0` (Risk) |
| A2 | No migrations — `ddl-auto: update` |
| A3 | Proto contracts duplicated (+ stray copy) |
| A4 | Kafka configured twice (YAML + Java config); unused `KafkaTemplate` injected into `PatientService` |
| A5 | Only "created" events; update/delete don't notify billing or analytics |
| A6 | `GET /patients` unpaginated |
| A7 | Billing (hardcoded `12345`, no DB) and analytics (log only) are stubs |
| A8 | Compose: `apache/kafka:latest` unpinned; no app healthchecks; `depends_on: kafka` only `service_started`; no restart policies |

### 4.4 Missing tests

Only empty `contextLoads` tests exist (and cannot run — B7). Missing: service unit tests, `@WebMvcTest`, `@DataJpaTest`, gRPC (in-process), Kafka producer/consumer, gateway filter, Testcontainers integration, security tests.

### 4.5 Missing production capabilities

Health/readiness, metrics, structured logs, tracing/correlation IDs, timeouts/retries/circuit breakers, dead-letter topics, idempotency, rate limiting, caching, CI, image registry and scanning, Kubernetes manifests, IaC, secrets manager, TLS, backups.

## 5. Phases

| Phase | Content | Depends on | Complexity |
|---|---|---|---|
| **0. Fixes & foundation** | B1 (interim: rollback + 503) – B11 except B7 (→ phase 1) · align all services on Boot 4 · Flyway · Actuator health · pagination · remove PII from logs · shared proto module · repo hygiene · README and `.http` fixes | – | Medium |
| **1. Testing safety net** | Fix B7 · JUnit 5 + Mockito + AssertJ · `@WebMvcTest` / `@DataJpaTest` · Testcontainers (Postgres, Kafka) · in-process gRPC tests · fix E2E assertions | 0 | Medium |
| **2. CI + Docker Hub** | GitHub Actions per service (path filters) · build/test · images pushed to Docker Hub tagged by git SHA · Dependabot · Trivy scan · Docker improvements (BuildKit cache, layered jars, pinned tags, `.dockerignore`) | 1 | Low–Medium |
| **3. Spring Security + Auth0** | Gateway and all services as OAuth2 resource servers validating Auth0 JWTs via JWKS (replaces per-request `/validate`) · roles/permissions + `@PreAuthorize` · patient ownership · billing gRPC service-to-service auth (client credentials) · CORS · decide the future of `auth-service` · test strategy with a mock issuer | 0, 1 | High |
| **4. Reliability & messaging** | Transactional outbox (real fix for B1) · migrate billing gRPC from `net.devh` to Spring gRPC · message keys, idempotent consumers, `DefaultErrorHandler` + retries + DLT · Resilience4j around gRPC · update/delete events · schema-evolution rules | 1, 3 | High |
| **5. Observability** | Micrometer → Prometheus + Grafana · structured JSON logs → Loki · OpenTelemetry tracing → Tempo across HTTP/gRPC/Kafka · correlation IDs · dashboards and alerts | 0 | Medium |
| **6. Load balancing, caching, rate limiting** | Redis rate limiter on the gateway (protects login and APIs) · Redis cache via Spring Cache (PII-aware) · multiple replicas behind load balancing | 5 | Medium |
| **7. Kubernetes + Helm** | kind/k3d locally · Deployments with Actuator probes, Services, ConfigMaps/Secrets, Ingress · Helm chart · Kafka via Strimzi | 2, 5 | High |
| **8. AWS + Terraform + CD** | Terraform with S3 remote state · VPC, ECR, RDS, Secrets Manager · EKS (reuses phase 7) or ECS Fargate · MSK Serverless or in-cluster Kafka · GitHub OIDC → AWS · CD via GitHub Actions or Argo CD | 2, 7 | Very high |

### Why this order

- **Tests before security** — phase 3 touches every service; tests tell "security works" apart from "routing broke".
- **CI right after tests** — cheap once tests exist, and every PR is checked from then on.
- **Observability before LB/caching** — you cannot show an improvement you cannot measure.
- **Kubernetes before AWS** — learn orchestration locally for free, then reuse the same Helm chart on EKS.

### Dependency graph

```
0 ──► 1 ──► 2 ──────────────► 7 ──► 8
│     │                       ▲
│     └──► 3 ──► 4            │
└──────────► 5 ──► 6          │
             └────────────────┘
```

## 6. Technology choices

| Need | Choice | Alternatives | Reason |
|---|---|---|---|
| Identity | **Auth0** | Keycloak, Cognito, own auth-service | Hosted OIDC, matches industry practice |
| Migrations | Flyway | Liquibase | SQL-first, simple, Boot default |
| Integration tests | Testcontainers | H2, embedded Kafka | Real Postgres/Kafka; no H2-vs-prod surprises |
| Resilience | Resilience4j | Spring Retry | Timeout + retry + circuit breaker with Micrometer metrics |
| Reliable events | Outbox table + poller | Debezium CDC | Easy to understand; Debezium as later upgrade |
| Observability | Prometheus, Grafana, Loki, Tempo, OpenTelemetry | ELK, Datadog | Free, standard, single Grafana UI |
| Rate limit / cache | Redis | Bucket4j, Caffeine | Shared state across replicas |
| Load balancing | Kubernetes Services (NGINX for compose only) | Eureka + Spring Cloud LoadBalancer | Kubernetes already provides discovery — no Eureka |
| CI | GitHub Actions | Jenkins, GitLab CI | Repo is on GitHub |
| Packaging | Helm | Kustomize | Templating + versioned releases |
| IaC | Terraform | CDK, Pulumi | Cloud-neutral, most in demand |
| Compute | EKS or ECS Fargate (decide in phase 8) | – | EKS reuses phase 7; Fargate is cheaper/simpler |

## 7. Risks and considerations

- **Gateway Boot 3.4 → 4 upgrade** is the riskiest single change (Spring Cloud Gateway artifact/property renames). Done in phase 0, verified by phase 1 tests.
- **Library pins** (`spring-kafka`, `springdoc`, `net.devh` gRPC) must move to Boot-4-compatible versions; gRPC starter migration is its own step.
- **Shared proto module without a parent POM** must be installed/published before services build — affects Dockerfiles and CI.
- **Auth0 needs internet**; automated tests must not depend on the real tenant (use a local mock issuer / signed test JWTs). Auth0 secrets go in `.env` / GitHub secrets, never in the repo.
- **Outbox** makes billing eventually consistent — a product decision, discussed in phase 4.
- **AWS cost**: EKS control plane, NAT gateways, MSK and RDS add up. Use budgets/alerts and `terraform destroy` after sessions.
- **Health data**: treat logs, caches and test fixtures as sensitive from phase 0 on.

## 8. Documentation layout

```
docs/
  implementation-plans/
    roadmap.md                          ← this file
    phase-00-fixes-and-foundation.md    ← created when phase 0 starts
    phase-01-testing.md
    phase-02-ci-docker-hub.md
    phase-03-security-auth0.md
    phase-04-reliability-messaging.md
    phase-05-observability.md
    phase-06-lb-cache-ratelimit.md
    phase-07-kubernetes-helm.md
    phase-08-aws-terraform-cd.md
```

## 9. Progress

| Phase | Branch | Status |
|---|---|---|
| 0 | `feature/phase-00-fixes-and-foundation` | ✅ done (2026-11-16) — [phase-00](phase-00-fixes-and-foundation.md) |
| 1 | `feature/phase-01-testing` | ✅ done (2026-11-17) — [phase-01](phase-01-testing.md) |
| 2 | `feature/phase-02-ci-docker-hub` | 🚧 in progress — [phase-02](phase-02-ci-docker-hub.md) |
| 3 | – | not started |
| 4 | – | not started |
| 5 | – | not started |
| 6 | – | not started |
| 7 | – | not started |
| 8 | – | not started |
