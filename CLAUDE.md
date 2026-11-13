# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Spring Boot microservices (Java 21, Maven), each service is an **independent Maven project** with its own `mvnw` — there is no root/parent POM. Services: `api-gateway` (4004), `auth-service` (4005), `patient-service` (4000), `billing-service` (HTTP 4002, gRPC 9001), `analytics-service` (4003). `Integration-tests/` is a separate Maven project (REST Assured) with no wrapper. `api-requests/` and `grpc-requests/` hold IntelliJ `.http` files for manual testing.

The former `infrastructure/` (AWS CDK / LocalStack) folder was removed intentionally; the README's AWS/LocalStack sections are outdated. The gateway's `application-prod.yml` was written for that deployment and is currently unused.

Spring Boot versions are **not aligned**: `api-gateway` is on Boot 3.4.1 (Spring Cloud Gateway, reactive/WebFlux), the other services are on Boot 4.0.x (servlet/WebMVC). Check the service's `pom.xml` before assuming an API or starter name.

## Commands

No JDK or Maven is installed on the host — build and test through Docker:

```bash
# Run the whole stack (requires a root .env, see README for the variable list)
docker compose up --build
docker compose up --build patient-service      # rebuild one service
docker compose down -v                         # also wipes DB volumes (needed after changing DB creds in .env)

# Build/test a single service without a local JDK (cache deps in a named volume)
docker run --rm -v "$PWD/patient-service":/ws -v pm-m2-cache:/root/.m2 -w /ws \
  maven:3.9.9-eclipse-temurin-21 mvn -B test

# Single test class / method
... mvn -B test -Dtest=PatientServiceApplicationTests
... mvn -B test -Dtest=ClassName#methodName
```

With a local JDK, the equivalent is `./mvnw test` inside a service directory. Integration tests (`cd Integration-tests && mvn test`) hit `http://localhost:4004` and require the full compose stack running; they log in as `testuser@test.com` / `password123`, which is seeded by `auth-service/src/main/resources/data.sql`.

Each service's Dockerfile is a multi-stage Maven build (`mvn package -DskipTests`), so `docker compose up --build` compiles from source — no need to pre-build jars.

## Architecture

**Request flow:** client → `api-gateway` → downstream service. Gateway routes are defined in `api-gateway/src/main/resources/application.yml`:
- `/auth/**` → auth-service (prefix stripped, so `/auth/login` → `/login`)
- `/api/patients/**` → patient-service (prefix stripped → `/patients/**`), guarded by the custom `JwtValidation` filter
- `/api-docs/{patients,auth}` → each service's `/v3/api-docs`

**Auth:** `auth-service` issues HS256 JWTs (jjwt, secret from `JWT_SECRET`) and exposes `GET /validate`. The gateway's `JwtValidationGatewayFilterFactory` does **not** verify tokens itself — it forwards the `Authorization` header to auth-service `/validate` per request. Downstream services (patient, billing, analytics) have no security of their own.

**Patient creation fan-out** (`PatientService.createPatient`):
1. Persist to PatientDB (Postgres, JPA, `ddl-auto: update` — no migrations tool).
2. Synchronous gRPC call to billing-service `CreateBillingAccount` via `BillingServiceGrpcClient` (address/port from `BILLING_SERVICE_ADDRESS`/`BILLING_SERVICE_PORT`).
3. Publish a protobuf-serialized `PatientEvent` (as `byte[]`) to Kafka topic `patient`; `analytics-service` consumes it with `@KafkaListener(topics = "patient")` and currently only logs it.

**Protobuf contracts are duplicated, not shared.** `billing_service.proto` lives in both `patient-service/src/main/proto` and `billing-service/src/main/proto`; `kafka_service.proto` lives in both `patient-service` and `analytics-service` (analytics also has a stray copy at `src/main/kafka_service.proto`). When changing a contract, update every copy. Java classes are generated at build time by `protobuf-maven-plugin` (+ `os-maven-plugin` extension) into `target/` — they are not checked in.

**Configuration:** each service's `application.yml` holds non-secret defaults with `${ENV:default}` placeholders; secrets (DB creds, `JWT_SECRET`) come only from the root `.env` via `docker-compose.yml`, which uses `${VAR:?}` so compose fails fast if one is missing. Two Postgres containers: patient DB on host port 5000, auth DB on 5001. Kafka (KRaft, single node) is `kafka:9092` inside the network and `localhost:9094` from the host.
