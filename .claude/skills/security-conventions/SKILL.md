---
name: security-conventions
description: How to secure backend services with OAuth2 / OIDC access tokens from an external identity provider - authentication vs authorization, verifying JWTs locally (issuer, audience, expiry, signature via JWKS), defence in depth (gateway AND each service), permissions per endpoint, roles for people vs permissions for programs, service-to-service tokens (client credentials) over HTTP and gRPC, 401 vs 403 with one error format, CORS, where clients keep tokens, per-object rules (ownership, group admins) in your own data, and testing without the real provider. Stack-agnostic principles (part A) plus the concrete Spring Security 7 + Auth0 + gRPC setup of this repo (part B1) and equivalents for other stacks and providers (part B2). Use when adding or changing an endpoint, a gRPC method, a service-to-service call, security config, token/issuer settings, CORS, permissions or roles, when a request gets 401/403 unexpectedly, or when writing tests that need tokens.
---

# Security conventions (established in roadmap Phase 3)

**How to use this skill:** part A holds for any language and any identity provider. Then use the
section that matches the project: B1 for Java/Spring Boot + Auth0 (this repo), B2 for other stacks.
Background, diagrams and decisions: `docs/implementation-plans/phase-03-security-auth0.md`.
Comment style (`PHASE n (step x.y)`, replaced code kept as `PHASE n (old)`) comes from
`service-conventions`; security code especially needs comments that say **how the pieces connect**.

---

## A. Principles (any stack)

### A1. Two questions, two answers

| Question | Name | Answered by | Failure |
|---|---|---|---|
| Who are you? | **authentication** | a valid token (signature, issuer, audience, expiry) | **401** + `WWW-Authenticate: Bearer` |
| What may you do? | **authorization** | a permission in the token, or a rule on your own data | **403** |

Never mix them up: a valid token with a missing permission is 403, not 401 ("log in again" would not help).

### A2. Don't build login yourself

- Use an **identity provider** (Auth0, Keycloak, Cognito, Entra ID, Okta). It stores users and passwords,
  runs the login page, MFA, resets and brute-force protection, and **signs tokens with a private key**.
- Your services only **verify** tokens with the provider's **public keys** (JWKS). They never see passwords.
- Asymmetric signing (RS256/ES256): a leaked public key forges nothing. A shared secret (HS256) that both
  creates and checks tokens is only acceptable inside one single service.

### A3. Verify every token locally - all four checks

1. **signature** with a key from the issuer's JWKS (fetched once, cached, refreshed on unknown `kid`)
2. **issuer** (`iss`) equals exactly the configured issuer (watch trailing slashes)
3. **audience** (`aud`) contains *this* API - a valid token for another API of the same tenant must fail
4. **expiry** (`exp`, small clock skew)

Use the framework's verifier, configured with issuer + audience. **Never write your own JWT parsing.**
No network call per request; no central "/validate" endpoint (a single point of failure and latency).
Settings without defaults: a service that doesn't know its issuer must refuse to start.

### A4. Defence in depth

- The **gateway** rejects requests without a valid token early (cheap, one place for CORS and rate limits).
- **Each service checks again** and decides permissions itself. "Inside the network" is not "trusted":
  ports are reachable without the gateway, and gateway bugs happen.
- **Deny by default:** every path or RPC method not explicitly configured is closed. Health checks
  and API docs are opened explicitly.
- The service that **owns the data** owns its permission rules. The gateway doesn't need to know them.

### A5. Permissions, roles, and who gets what

- **Permissions** are named actions on an API: `read:patients`, `write:patients`, `delete:patients`.
  The provider defines them; the token carries them; the code **requires** them per endpoint.
- **People** get permissions through **roles** (`staff` = read + write, `admin` = + delete).
  **Programs** get permissions directly on their own client/app.
- Changes in the provider take effect with the **next token**, not immediately. That's why tokens are short-lived.
- **Least privilege:** each caller gets only what it needs (a service that creates billing accounts gets
  only that permission, not the patient permissions).
- Put the rule **next to the endpoint** (annotation/decorator per method) so a missing rule stands out in
  review. Dangerous operations (delete, export) get their own permission.
- Watch the **order of checks**: frameworks often validate the request body *before* method-level
  permission checks, so an unauthorized caller can get 400 instead of 403. Use URL-level rules if that matters.

### A6. Per-object rules live in your own data

"May Anna delete **this** patient / remove a member from **group 42**?" is not a permission in the token.
It depends on data that changes constantly. Store it in your DB (e.g. `group_members(group_id, user_sub, role)`,
`patients.owner_sub`) and check it in the service, using the token's `sub` as the user id:

```
1. token valid?                       -> provider (A3)          else 401
2. may use this kind of action?       -> permission (A5)        else 403
3. may do it on THIS object?          -> your data + service    else 403 (or 404 to hide existence)
```

For complex sharing graphs, relationship-based tools exist (OpenFGA / Auth0 FGA, SpiceDB).

### A7. Service-to-service calls

- A calling service uses **its own identity**, not the user's token: the **client credentials** grant
  (client id + secret → token with the service's permissions). In Kubernetes/cloud: workload identity.
- Never forward a user's token "to get through" another service's door.
- **Cache** the service token until shortly before it expires (one request to the provider per token
  lifetime, not per call). Free tiers limit machine-to-machine tokens.
- Put the token on every call (HTTP header, gRPC metadata `authorization: Bearer ...`). The callee checks
  it like any other token, plus the permission per method.
- If no token can be obtained, fail the call cleanly (e.g. 503 + rollback), never call without one.
- Inside a plain network, tokens travel unencrypted: add TLS/mTLS between services when you leave a
  local setup.

### A8. Tokens on the client side

| Token | Lifetime | Job |
|---|---|---|
| access token | short (5-15 min for people; up to 24 h for servers) | sent with every API call |
| refresh token | days-weeks, rotated, revocable | gets new access tokens without a new login |
| ID token | for the frontend only | user info for display - **never** accepted by APIs (audience check rejects it) |

- Web apps: prefer a **backend-for-frontend**: tokens stay on the server, the browser gets an
  `HttpOnly; Secure; SameSite` session cookie. SPA without a backend: access token in memory only,
  refresh token rotation via the provider SDK. **Not** long-lived tokens in `localStorage`.
- Mobile: refresh token in the OS secure storage (Keychain/Keystore).
- Never log tokens, never print them in tests or CI logs, never commit them.

### A9. Errors, CORS, secrets

- 401/403 use the **same error format** as all other errors (RFC 9457 Problem Details), written by the
  security layer (errors happen before the controller). Don't echo the exact reason ("signature invalid").
- **CORS** only where browsers call you (the gateway), with an explicit origin list. The browser's
  preflight (`OPTIONS`) carries no token and must be answered before authentication.
- No sessions, no cookies for APIs → CSRF protection off, stateless.
- Client secrets only in secret storage (`.env` locally, a secret manager in the cloud), one per service.

### A10. Testing without the real provider

| Level | Technique |
|---|---|
| unit / web slice | the framework's mock user, or a **test decoder** (token string → claims table) that still runs your real claim → permission mapping |
| gateway / filters | **sign your own tokens** with a test key pair and serve its JWKS from a stub, so the real verification runs, incl. wrong issuer/audience, expired, forged |
| service-to-service | a fake token endpoint (records the request: grant, client id, audience; counts calls → caching) + a fake callee that records the auth header |
| end-to-end / CI | a **local fake issuer container** (e.g. mock-oauth2-server) - no internet, no provider secrets in CI |

Every protected endpoint gets at least: no token → 401, wrong permission → 403, right permission → success.

---

## B1. This repo: Spring Boot 4 / Spring Security 7 + Auth0 + gRPC

### Setup in Auth0 (manual, see the phase file "What you need to do")

- API `Patient Management API`, identifier (audience) `https://api.patient-management.local`, RS256,
  **Enable RBAC** + **Add Permissions in the Access Token** → claim `permissions`.
- Permissions: `read:patients`, `write:patients`, `delete:patients`, `create:billing-accounts`.
- Apps: a test app (Client Access: read + write), `patient-service` (Machine to Machine, Client Access:
  only `create:billing-accounts`). Roles for people under User Management → Roles.

### Accepting tokens (resource server)

| Service | Starter | Config | Security code |
|---|---|---|---|
| api-gateway (reactive) | `spring-boot-starter-security-oauth2-resource-server` | `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${AUTH0_ISSUER_URI}`, `audiences: ${AUTH0_AUDIENCE}` | `config/SecurityConfig` (`SecurityWebFilterChain`, `@EnableWebFluxSecurity`), `ProblemDetailsWriter`, CORS bean |
| patient-service (servlet) | same | same + `authorities-claim-name: permissions`, `authority-prefix: ""` | `config/SecurityConfig` (`SecurityFilterChain`, `@EnableMethodSecurity`) + `@PreAuthorize("hasAuthority('read:patients')")` per controller method |
| billing-service (gRPC) | same (for the `JwtDecoder` bean) | issuer + audiences | `grpc/BearerTokenInterceptor` (`@GrpcGlobalServerInterceptor`): metadata → `JwtDecoder.decode` → method → permission map; deny by default; `grpc.health.v1.Health` + reflection public. HTTP side: `config/SecurityConfig` (health only) |

All chains: stateless, CSRF off, `denyAll()` for unplanned paths, entry point + access-denied handler
write Problem Details (`WWW-Authenticate: Bearer` / `Bearer error="invalid_token"`).

### Getting tokens (patient-service → billing)

- `spring-boot-starter-security-oauth2-client`; registration `billing` with `client_credentials`,
  `client_secret_post`, `token-uri: ${AUTH0_TOKEN_URI:${AUTH0_ISSUER_URI}oauth/token}`.
- `config/ServiceTokenConfig`: `RestClientClientCredentialsTokenResponseClient` with
  `setParametersCustomizer(p -> p.add("audience", audience))` (Auth0 needs it) inside an
  `AuthorizedClientServiceOAuth2AuthorizedClientManager` (works outside web requests, caches the token).
- `grpc/ServiceTokenProvider.token()` → `grpc/BearerTokenCallCredentials` (gRPC `CallCredentials`, puts
  `authorization: Bearer ...` into metadata; failure → `UNAUTHENTICATED`) →
  `BillingServiceGrpcClient`: `newBlockingStub(channel).withCallCredentials(...)`.
- Any gRPC failure (incl. `UNAUTHENTICATED`/`PERMISSION_DENIED`) → `BillingServiceUnavailableException` → 503 + rollback.

### Configuration

| Variable | Where | Value |
|---|---|---|
| `AUTH0_ISSUER_URI` | gateway, patient, billing | `https://<tenant>.<region>.auth0.com/` (**trailing `/`**) |
| `AUTH0_AUDIENCE` | gateway, patient, billing | `https://api.patient-management.local` |
| `PATIENT_SERVICE_CLIENT_ID` / `_SECRET` | patient | Auth0 app `patient-service` (`.env` only) |
| `AUTH0_TOKEN_URI` | patient | optional; E2E points it at the mock |
| `CORS_ALLOWED_ORIGINS` | gateway | default `http://localhost:3000` |

All via `.env` → `docker-compose.yml` with `${VAR:?}`; no defaults in `application.yml`.

### Tests

- Gateway: `support/TestTokens` (Nimbus `RSAKeyGenerator`, signs tokens, serves JWKS via `StubHttpServer`;
  test sets `jwk-set-uri` to the stub) → `config/GatewaySecurityTest`.
- patient-service: `@Import(SecurityConfig.class)` in `@WebMvcTest` (slices don't pick up own `@Configuration`);
  `@WithMockUser(authorities = {...})` for non-security tests; `PatientSecurityTest` with a table-based
  `JwtDecoder` `@TestConfiguration` (bearer token values must match `[A-Za-z0-9-._~+/]+`, so no `:` in them);
  `FakeTokenEndpoint` + `FakeBillingServer.authorizations()` for the service token.
- billing: `BillingGrpcServiceTest` with a test `JwtDecoder` and `MetadataUtils.newAttachHeadersInterceptor`.
- `src/test/resources/application.properties` provides placeholder `AUTH0_*` (and client id/secret) values;
  decoders are lazy, so nothing is fetched at startup.
- E2E: `docker-compose.e2e.yml` (mock-oauth2-server `ghcr.io/navikt/mock-oauth2-server:7.0.1`, claims per
  `client_id` in `Integration-tests/mock-issuer/mock-oauth2-server.json`); tests send
  `Host: mock-oauth2-server:8080` so `iss` matches what the services trust; CI sets
  `COMPOSE_FILE=docker-compose.yml:docker-compose.e2e.yml`.

```bash
scripts/mvn-test.sh api-gateway -Dtest=GatewaySecurityTest
scripts/mvn-test.sh patient-service -Dtest='PatientSecurityTest,BillingServiceGrpcClientTest'
scripts/mvn-test.sh billing-service
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build --wait   # then Integration-tests
```

### Adding something

- **New REST endpoint:** `@PreAuthorize` with its permission; new permission → add it in Auth0 (API →
  Permissions) and to the roles/apps that need it; tests: 401 / 403 / success; mock JSON if E2E uses it.
- **New gRPC method:** add it to `REQUIRED_PERMISSION` in `BearerTokenInterceptor` (otherwise it's denied).
- **New service calling another:** its own Auth0 M2M app + permission, client registration, credentials on the
  client; callee verifies token + permission.
- **New service accepting tokens:** resource-server starter, issuer/audience env in compose (+ E2E override),
  its own `SecurityConfig` (stateless, deny by default, Problem Details).

### Gotchas

- 401 for every token → `AUTH0_ISSUER_URI` without trailing `/`, or wrong audience.
- 403 although the permission is in Auth0 → old token (get a new one), or it was set under "User Delegated
  Access" instead of "Client Access" (machine tokens), or RBAC "Add Permissions in the Access Token" is off.
- `permissions: []` in an M2M token → the app isn't authorized for the API under Client Access.
- Adding Spring Security to a service locks **all** HTTP paths (incl. health) until you define a chain.
- `client_credentials` with Auth0 without `audience` → token for no API → 401 everywhere.

---

## B2. Other stacks and providers

| Concern | Node / TypeScript | Python | Go | .NET |
|---|---|---|---|---|
| Verify JWT (iss/aud/exp/JWKS) | `express-oauth2-jwt-bearer`, `jose` (`createRemoteJWKSet` + `jwtVerify`), NestJS `passport-jwt` | FastAPI + `PyJWT` (`PyJWKClient`) or `authlib`; Django: `djangorestframework-simplejwt` (own tokens) / `mozilla-django-oidc` | `github.com/coreos/go-oidc` (`Verifier`), `lestrrat-go/jwx` | `AddAuthentication().AddJwtBearer(o => { o.Authority; o.Audience; })` |
| Permission per endpoint | middleware `requiredScopes` / Nest `@UseGuards` + custom decorator | FastAPI dependency `Security(check, scopes=[...])` | middleware per route | `[Authorize(Policy = "read:patients")]` + policy with `RequireClaim("permissions", ...)` |
| Client credentials + cache | `openid-client`, or fetch + in-memory cache until `exp - 60s` | `authlib` `OAuth2Session.fetch_token`, `httpx-auth` | `golang.org/x/oauth2/clientcredentials` (`TokenSource` caches) | `Duende.AccessTokenManagement` / `IdentityModel` |
| gRPC token | `@grpc/grpc-js` `credentials.createFromMetadataGenerator`; server interceptor | `grpc.metadata_call_credentials`; server `ServerInterceptor` | `credentials.PerRPCCredentials` (`oauth.TokenSource`); `grpc.UnaryInterceptor` | `CallCredentials.FromInterceptor`; ASP.NET gRPC uses `[Authorize]` |
| Fake issuer in tests | `jose` to sign test tokens; mock-oauth2-server container | `jwcrypto`/`PyJWT` to sign; same container | `jwx` to sign; same container | `Microsoft.IdentityModel.Tokens` to sign; same container |

| Provider | Issuer / audience notes | Permissions claim |
|---|---|---|
| **Auth0** | issuer `https://<tenant>/` (trailing `/`); `audience` = API identifier, required in token requests | `permissions` (RBAC on) and `scope` |
| **Keycloak** | issuer `https://<host>/realms/<realm>`; audience needs an "audience" client-scope mapper | `realm_access.roles`, `resource_access.<client>.roles`, `scope` |
| **AWS Cognito** | issuer `https://cognito-idp.<region>.amazonaws.com/<poolId>`; access tokens have `client_id`, not `aud` → check `client_id` | `cognito:groups`, `scope` (resource-server scopes) |
| **Microsoft Entra ID** | issuer `https://login.microsoftonline.com/<tenant>/v2.0`; audience = app ID URI / client id | `roles` (app roles), `scp` (delegated) |

The code pattern stays the same with every provider: issuer + audience in configuration, map the provider's
permission claim to the framework's authorities once, require permissions per endpoint.
