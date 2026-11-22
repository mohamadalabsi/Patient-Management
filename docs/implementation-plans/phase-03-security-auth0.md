# Phase 3 — Spring Security + Auth0

| | |
|---|---|
| Branch | `feature/phase-03-security-auth0` (from `dev-branch` after PR #4) |
| Depends on | Phase 1 (tests tell "security works" apart from "routing broke"), Phase 2 (CI runs them) |
| Status | 🟡 in progress - plan waiting for approval |
| Roadmap row | Gateway and services validate Auth0 JWTs via JWKS · permissions + `@PreAuthorize` · billing gRPC service-to-service auth (client credentials) · CORS · future of `auth-service` · tests with a mock issuer |

## How to read this file

You should understand the whole phase from this file alone, without opening the code.
Start with **Security in plain words**, then the **Technology map**. Every step says *what* is done,
*why*, *how*, *when* you would use the same technique elsewhere, and *how it is verified*.
**"What you need to do"** lists everything only you can do (the Auth0 account, `.env` values), and
**Run order** says exactly when. In the code every change gets a `PHASE 3 (step 3.x)` comment that
explains "we do this, so now that happens, and this is how it connects to the next piece";
replaced code stays as `PHASE 3 (old)`.

## Decisions already made (2026-11-22)

- **`auth-service` is retired.** Auth0 does login and issues tokens now. Its code stays in the repo
  unchanged (CI still tests it), but it is commented out of `docker-compose.yml` and the gateway
  routes as `PHASE 3 (old)`.
- **CI uses a local fake token server** (`mock-oauth2-server`), not the real Auth0. No internet, no
  Auth0 secrets in GitHub. Locally you use your real Auth0 tenant.

## Security in plain words (read this first)

### Two questions: who are you, and what may you do?

- **Authentication = "who are you?"** Proved with a **token**: a signed piece of text that says
  "this is user X, valid until 14:00, signed by Auth0".
- **Authorization = "what may you do?"** The token also lists **permissions** like
  `read:patients`. A service checks "does this token have the permission this endpoint needs?".

### How it works today (before this phase)

```
client ── POST /auth/login ──► auth-service      (checks password, creates a token with OUR secret)
client ── GET /api/patients ─► api-gateway ──── "is this token ok?" ──► auth-service /validate
                                    │                                   (one extra call per request!)
                                    └── yes ──► patient-service         (trusts everybody, no checks)
```

Problems:
- **Every request costs an extra network call.** If auth-service is slow or down, every request
  fails.
- **We store passwords ourselves.** Hashing, resets, MFA and brute-force protection are hard to get
  right.
- **Nothing after the gateway checks anything.** patient-service and billing-service accept any
  request that reaches them.
- **There are no permissions.** A valid token can do everything.

### How it works after this phase

```
                     (1) login                    ┌───────────────┐
client ─────────────────────────────────────────► │ Auth0         │  checks the password, signs the
   ▲                                              │ (the "issuer")│  token with its PRIVATE key
   └──────────── (2) access token ◄────────────── └───────┬───────┘
                                                          │ publishes its PUBLIC keys at
                                                          │ /.well-known/jwks.json (fetched once,
                                                          │ then cached by every service)
client ── (3) GET /api/patients + token ──► api-gateway ──┤ verifies signature, issuer, audience,
                                               │          │ expiry LOCALLY -> no extra call
                                               │ (4) passes the same token on
                                               ▼          │
                                         patient-service ─┤ verifies again + checks the permission
                                               │          │ ("read:patients"?) -> 403 if missing
                                               │ (5) gRPC + ITS OWN token (client credentials)
                                               ▼          │
                                         billing-service ─┘ verifies + checks "create:billing-accounts"
```

- **Asymmetric keys.** Only Auth0 has the private key that signs tokens. Every service gets the
  public key, which can only *check* signatures. A leaked public key can't be used to forge a token.
  Today one shared secret both creates and checks tokens.
- **Defence in depth.** The gateway rejects obviously bad requests early. Each service still checks
  for itself, because "inside the network" is not the same as "trusted".
- **Two kinds of tokens:**
  - a **user token**, for a person using the API;
  - a **service token**: patient-service gets its own token from Auth0 with a client id and secret
    (**client credentials**) to call billing. Billing then knows *which service* is calling, not
    just that some user is logged in.

### What a token looks like (decoded)

```json
{
  "iss": "https://<your-tenant>.eu.auth0.com/",          // who issued it     -> must be OUR tenant
  "aud": "https://api.patient-management.local",         // who it is for     -> must be OUR API
  "sub": "auth0|6542...",                                // who it is about   (user or service)
  "exp": 1763820000,                                     // expiry            -> rejected after this
  "permissions": ["read:patients", "write:patients"]     // what it may do    -> checked per endpoint
}
```

A service accepts the token only if **all** of these hold: the signature is valid with Auth0's public
key, `iss` is our tenant, `aud` is our API, and `exp` is in the future. If any check fails, the answer
is **401**. If the token is fine but the permission is missing, the answer is **403**.

## Out of scope

- **Patient ownership** ("a patient sees only their own record") needs a link between an Auth0 user
  and a patient row. It's an exercise below.
- **A login page or frontend.** CORS is configured so a future frontend can call the gateway.
- **Kafka authentication** (who may publish to the `patient` topic). Network-level; Phase 7/8.
- **Migrating billing's gRPC library.** That's Phase 4. The gRPC token check is written as a plain
  gRPC interceptor so it survives that move unchanged.

## Technology map

| Technology | What it is | Why we use it | Steps |
|---|---|---|---|
| **Auth0** | Hosted identity provider (logins, users, roles, tokens) | No passwords in our DB; standard OAuth2/OIDC; free tier | 3.1–3.3 |
| **OAuth2 / OIDC** | Standards for "get a token" and "who is the user" | Every provider speaks them, so the services aren't tied to Auth0 | all |
| **JWT + JWKS** | Signed JSON token + the issuer's published public keys | Services verify tokens offline, with no call per request | 3.1–3.3 |
| **Spring Security 7** (Boot 4.0.9) | Security framework for Spring | Does all the JWT checks from 2 properties (`issuer-uri`, `audiences`) | 3.1–3.3 |
| `spring-boot-starter-security-oauth2-resource-server` | "This app *accepts* tokens" | Gateway (reactive), patient, billing | 3.1–3.3 |
| `spring-boot-starter-security-oauth2-client` | "This app *gets* tokens" | patient-service fetches its own token to call billing | 3.3 |
| `@PreAuthorize` | Annotation: "this method needs permission X" | The rule sits right next to the endpoint | 3.2 |
| gRPC interceptor | Code that runs before every gRPC call | Reads the token from gRPC metadata and checks it | 3.3 |
| **mock-oauth2-server** (`ghcr.io/navikt/mock-oauth2-server:7.0.1`) | A fake token issuer in a container | E2E tests in CI need tokens without the internet or Auth0 secrets | 3.1 |
| `spring-security-test` | Fake tokens in unit tests (`jwt()`, `mockJwt()`) | Tests choose the permissions with no issuer at all | 3.1–3.3 |

## Versions

| Item | Version | Note |
|---|---|---|
| Spring Boot / Spring Security | 4.0.9 / 7.0.x (from the Boot BOM) | no manual pins |
| mock-oauth2-server | 7.0.1 | latest release, pinned in compose |
| Auth0 | free plan | 1 API, 1 M2M application, test tokens |

## What you need to do

| When | What | Where |
|---|---|---|
| **before step 3.1** | Create a free Auth0 account and a tenant (region EU) | auth0.com → Sign up |
| before 3.1 | Create the API: name `Patient Management API`, identifier `https://api.patient-management.local` (this is the **audience**), signing RS256 | Dashboard → Applications → **APIs** → Create API |
| before 3.1 | In that API: **Settings** → turn on **Enable RBAC** and **Add Permissions in the Access Token** → Save | APIs → Patient Management API → Settings |
| before 3.1 | **Permissions** tab: add `read:patients`, `write:patients`, `delete:patients`, `create:billing-accounts` | APIs → Patient Management API → Permissions |
| before 3.1 | Open the auto-created **"Patient Management API (Test Application)"** and give it `read:patients` + `write:patients` (not delete). You'll use its tokens to test by hand | Applications → APIs → your API → **Machine to Machine Applications** tab → expand it → tick permissions |
| before 3.1 | Add to the root `.env` (I never edit `.env`): `AUTH0_ISSUER_URI=https://<tenant>.<region>.auth0.com/` (**with** the trailing `/`) and `AUTH0_AUDIENCE=https://api.patient-management.local` | `.env` |
| **before step 3.3** | Create an application `patient-service`, type **Machine to Machine**, authorized for our API with **only** `create:billing-accounts` | Applications → Create Application |
| before 3.3 | Copy its Client ID and Client Secret into `.env` as `PATIENT_SERVICE_CLIENT_ID=` / `PATIENT_SERVICE_CLIENT_SECRET=` | that app → Settings |
| after merge | Optional cleanup: `JWT_SECRET`, `AUTH_DB_*` in `.env` are no longer used | `.env` |

**GitHub:** nothing to set. CI uses the mock issuer, so no Auth0 secrets go into GitHub.

## Steps

### Step 3.1 - The gateway checks tokens itself; auth-service retired; mock issuer for tests

**What**
- The gateway becomes an **OAuth2 resource server**: it checks every `/api/**` request's token
  locally against Auth0's public keys.
- The `JwtValidation` filter (one call to auth-service per request) is commented out as
  `PHASE 3 (old)`, along with the `/auth/**` routes.
- auth-service and its database are commented out of `docker-compose.yml`.
- 401/403 responses are RFC 9457 Problem Details, like today.
- CORS allows a configurable frontend origin.
- For tests: `mock-oauth2-server` joins the stack through a compose **override file**
  (`docker-compose.e2e.yml`), and the E2E tests get their tokens from it.

**Why** No extra call per request, no shared secret, and no password database of our own.

**How**
- `api-gateway/pom.xml`: the resource-server starter replaces what the filter did.
- `application.yml`: `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${AUTH0_ISSUER_URI}` and
  `audiences: ${AUTH0_AUDIENCE}`. Spring reads the issuer's `/.well-known/openid-configuration`,
  finds the JWKS URL, caches the keys, and checks signature + `iss` + `aud` + `exp` on every request.
- `SecurityConfig` (new, reactive `SecurityWebFilterChain`):
  - `/actuator/health/**` and `/api-docs/**` are public;
  - `/api/**` needs a valid token;
  - everything else is denied;
  - the entry point and access-denied handler write Problem Details.
- The gateway forwards the `Authorization` header unchanged, so patient-service gets the same token
  (step 3.2).
- E2E: `docker-compose.e2e.yml` adds `mock-oauth2-server` and points every service's issuer at it.
  `Integration-tests` ask it for a token (client credentials, with the permissions we choose)
  instead of calling `/auth/login`. The CI `e2e` job uses the override file. Its throwaway `.env` no
  longer needs `JWT_SECRET`/`AUTH_DB_*`.
- Tests: `GatewayJwtFilterTest` (old filter) stays, but is disabled with a `PHASE 3 (old)` note. A
  new gateway test uses `mockJwt()` to check: no token → 401 with Problem Details; valid token →
  forwarded with its header; health → public.

**When** Whenever a service *receives* tokens from an external issuer: configure issuer + audience
and let the framework verify them. Never write your own signature check.

**Verified**
- gateway tests pass;
- `docker compose up` with your Auth0 values, then `curl` with an Auth0 test token → 200;
- no token / a bad token / a token for another audience → 401;
- E2E against the mock issuer passes.

### Step 3.2 - patient-service checks permissions itself

**What**
- patient-service also becomes a resource server.
- Each endpoint declares its permission:

  | Endpoint | Permission |
  |---|---|
  | `GET` | `read:patients` |
  | `POST`, `PUT` | `write:patients` |
  | `DELETE` | `delete:patients` |

- Missing permission → **403** Problem Details.

**Why**
- **Defence in depth:** if someone reaches patient-service directly (port 4000, or a gateway bug),
  it still refuses.
- Permissions belong to the service that owns the data. The gateway doesn't need to know what
  each endpoint does.

**How**
- `SecurityConfig` with `@EnableMethodSecurity`, stateless, CSRF off (no cookies, only bearer
  tokens).
- A small converter maps the token's `permissions` claim to Spring authorities, so
  `@PreAuthorize("hasAuthority('read:patients')")` works on the controller methods.
- `GlobalExceptionHandler` gets 401/403 Problem Details.
- Tests:
  - `PatientControllerTest` (`@WebMvcTest`) uses `jwt().authorities(...)`: right permission →
    200/201, wrong one → 403, none → 401;
  - the Testcontainers integration tests get a test token the same way.

**When** Any service that owns data: check permissions where the data lives, close to the method.

**Verified**
- unit + integration tests pass;
- manually: the Auth0 test application (read + write) can list and create, but `DELETE` → 403;
- E2E passes (the mock token carries all three permissions).

### Step 3.3 - Service-to-service: patient-service calls billing with its own token

**What**
- patient-service gets a **client-credentials** token from Auth0 (as "patient-service") and sends
  it in the gRPC metadata of `CreateBillingAccount`.
- billing-service checks it and requires `create:billing-accounts`. Otherwise the call fails with
  gRPC `UNAUTHENTICATED`/`PERMISSION_DENIED`, and patient-service rolls back (HTTP 503, as today).

**Why**
- Today anything in the network can create billing accounts.
- A *service* token, not the user's token: billing trusts the **caller service**, and the user's
  permissions don't have to include billing.

**How**
- patient-service:
  - oauth2-client starter with a registration `billing` (`client_credentials`, plus the `audience`
    parameter Auth0 needs);
  - a gRPC `ClientInterceptor` that puts `authorization: Bearer <token>` on each call;
  - Spring caches the token until shortly before it expires, so Auth0 is asked about once per
    token lifetime, not once per patient.
- billing-service:
  - resource-server starter (decoder only, from the same `issuer-uri`/`audiences`);
  - a gRPC `ServerInterceptor` (registered with `@GrpcGlobalServerInterceptor`) that reads the
    header, decodes the token and checks the permission;
  - its HTTP side stays as it is (only actuator).
- E2E: the mock issuer issues the `patient-service` client a token with `create:billing-accounts`.
- Tests:
  - billing: the in-process gRPC test calls with no token / a wrong permission / the right one;
  - patient: the client test checks the header is sent.

**When**
- Any backend → backend call: each service has its own identity (client credentials, or in
  Kubernetes/cloud a workload identity).
- Never forward a user token just to get through another service's door.

**Verified**
- tests pass;
- with Auth0: creating a patient → billing logs the accepted call, and the patient is created;
- with a wrong secret → 503 and no patient row;
- E2E passes.

### Step 3.4 - Docs

README (security section, new `.env` variables, how to get a test token), CLAUDE.md (auth
architecture rewritten), roadmap Progress → done, "What changed" in this file.

### Step 3.5 - Skill `security-conventions`

New `.claude/skills/security-conventions/SKILL.md` plus a row in `.claude/README.md`.
- **A (any stack):** authN vs authZ, verify tokens locally (issuer/audience/expiry/signature),
  defence in depth, permissions at the data owner, service identities, 401 vs 403, test tokens
  without a real issuer, secrets.
- **B1 (this repo):** Spring Security 7 / Auth0 / gRPC interceptors / mock-oauth2-server, with exact
  files and commands.
- **B2 (other stacks):** Node (express-oauth2-jwt-bearer / passport-jwt), Python (FastAPI +
  PyJWT / Authlib), Go (go-oidc), .NET (JwtBearer), and Keycloak/Cognito/Entra instead of Auth0.

## Run order - who does what

| # | Who | What |
|---|---|---|
| 1 | Claude | commit this plan (+ roadmap row "in progress") |
| 2 | **You** | read and approve the plan |
| 3 | **You** | Auth0: account, API, RBAC settings, permissions, test-application permissions; add `AUTH0_ISSUER_URI` + `AUTH0_AUDIENCE` to `.env`. Tell me when done |
| 4 | Claude | step 3.1: implement, run tests + stack + E2E, commit; stop and explain |
| 5 | **You** | push, check CI (I'll read the run) |
| 6 | Claude | step 3.2: implement, verify, commit; stop and explain |
| 7 | **You** | Auth0: create the `patient-service` M2M app; add its id/secret to `.env`. Push |
| 8 | Claude | step 3.3: implement, verify, commit; stop and explain |
| 9 | Claude | step 3.4 docs, then 3.5 skill, each its own commit |
| 10 | **You** | push, open the PR into `dev-branch`, merge when CI is green |

## Verification (end of phase)

- [ ] All per-service tests green (`scripts/mvn-test.sh <svc>`), CI green on the PR
- [ ] No token → 401, wrong audience/issuer/expired → 401, missing permission → 403, all Problem Details
- [ ] With your Auth0 test token: list + create patients work; delete → 403
- [ ] Creating a patient sends a service token to billing; a wrong client secret → 503, no row saved
- [ ] patient-service on port 4000 directly, without a token → 401
- [ ] E2E in CI passes with the mock issuer; no Auth0 secrets in GitHub
- [ ] auth-service no longer runs in compose; its code and tests are still in the repo

## Exercises

1. Decode your Auth0 token at jwt.io and find `iss`, `aud`, `exp`, `permissions`. Change one letter
   and send it: what status do you get, and why?
2. Remove `write:patients` from the test application, get a new token, try `POST`. What changes,
   and what doesn't?
3. Create a **Role** `staff` in Auth0 with read + write, assign it to a user, and explain how roles
   (for people) differ from permissions given directly to an M2M app (for services).
4. *Ownership:* sketch what a patient needs to see only their own record (a column with the Auth0
   `sub`, a check in the service). Which step's code would you change?

## Risks

- **Issuer mismatch with the mock server.** It writes the URL it was called with into `iss`. The
  tests (on the host, `localhost:<port>`) and the services (in Docker, `mock-oauth2-server:8080`)
  must see the same issuer. Fix: services fetch the keys via the internal `jwk-set-uri` and
  validate `iss` against the address the tests use. This is settled in step 3.1.
- **Auth0 trailing slash.** Auth0's `iss` ends with `/`. Without it in `AUTH0_ISSUER_URI`, every
  token gets a 401.
- **Auth0 free M2M quota.** It's limited per month. Token caching in step 3.3 keeps us far below it.
  CI uses none.
- **The stack needs internet locally** (Auth0). Offline, use the mock issuer override locally too.

## What changed

*(filled in at step 3.4)*
