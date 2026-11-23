---
name: security-conventions
description: How to secure a backend of microservices and its clients (mobile and web apps) with an external identity provider - registration and login (Authorization Code + PKCE, social login, email verification, MFA), where apps keep tokens (refresh tokens, secure storage, backend-for-frontend), user profiles in your own database keyed by the provider's user id, verifying JWTs locally (issuer, audience, expiry, JWKS), defence in depth (gateway and every service), permissions per endpoint, roles for people vs permissions for programs, per-object rules (owner, followers, group admins), service-to-service tokens (client credentials) over HTTP and gRPC, 401 vs 403, CORS, rate limits, account deletion, and testing without the real provider. Principles for any stack (part A), the concrete Java 21 / Spring Boot + Auth0 implementation (part B), equivalents for other stacks, providers and mobile SDKs (part C). Project-agnostic. Use when adding sign-up/login, an endpoint, a gRPC method, a service-to-service call, security config, token or issuer settings, roles/permissions, CORS, user provisioning or deletion, when a request gets 401/403 unexpectedly, or when writing tests that need tokens.
---

# Security conventions

**How to use this skill:** part A holds for every stack and identity provider. Part B is the default
implementation: Java / Spring Boot microservices with **Auth0**, for mobile and web clients. Part C maps
it to other stacks and providers. Skip what the project doesn't have (e.g. no self-registration, no
mobile app, no gRPC). Project-specific facts (tenant, API identifier, permission names, env variable
names) belong in the project's docs. Comment style from `service-conventions`: security code especially
needs comments that say **how the pieces connect**.

---

## A. Principles (any stack)

### A1. Two questions, two answers

| Question | Name | Answered by | Failure |
|---|---|---|---|
| Who are you? | **authentication** | a valid token (signature, issuer, audience, expiry) | **401** + `WWW-Authenticate: Bearer` |
| What may you do? | **authorization** | a permission in the token, or a rule on your own data | **403** (or 404 to hide that something exists) |

A valid token with a missing permission is 403, not 401 ("log in again" wouldn't help).

### A2. Don't build login yourself

- Use an **identity provider** (Auth0, Keycloak, Cognito, Entra ID, Okta, Firebase Auth). It stores
  users and password hashes, runs the login/sign-up pages, email verification, password reset, MFA,
  social login, brute-force and bot protection, and **signs tokens with a private key**.
- Your services only **verify** tokens with the provider's **public keys** (JWKS) and never see passwords.
- Asymmetric signing (RS256/ES256): a leaked public key forges nothing. A shared HMAC secret that both
  creates and checks tokens is not for multi-service systems.

### A3. Registration and login (people)

- Apps use **Authorization Code + PKCE** through the provider's hosted page in the **system browser**
  (iOS `ASWebAuthenticationSession`, Android Custom Tabs; web: redirect). Never collect the password in
  your own app form and never use the "password grant" - it skips MFA, bot protection and social login.
- Sign-up is the same flow with a "sign up" hint; social login (Google, Apple, ...) is a connection at the
  provider. iOS apps that offer other social logins must also offer **Sign in with Apple**.
- Require **verified email** before anything that relies on the email; don't trust an unverified
  `email` claim (the token says `email_verified`).
- Turn on the provider's attack protection (brute force, breached passwords, bot detection) and offer MFA.
- Separate tenants/environments for dev and production.

### A4. Users in your own system

- The provider owns **identity** (login, password, email verification). Your backend owns the **profile**
  (username, display name, avatar, bio, privacy settings, ...), stored in a user/profile service and keyed
  by the provider's stable user id (`sub`), never by email (emails change).
- **Provisioning:** the app, after the first login, calls your API (e.g. `PUT /me` / onboarding: choose a
  username). The service creates the profile if it doesn't exist (idempotent upsert on `sub`). Avoid
  making login depend on a provider hook calling your API synchronously.
- Usernames are unique **in your DB** (case-insensitive unique index); reserved names blocked.
- **Account deletion** (store requirement and GDPR): delete/anonymize the profile and content in your
  services (publish a `UserDeleted` event), revoke refresh tokens, delete the user at the provider
  (management API from a backend service with its own credentials).
- Blocking a user: block at the provider **and** revoke refresh tokens; access tokens die within minutes.

### A5. Tokens on the client side

| Token | Lifetime | Job |
|---|---|---|
| **access token** | short: 5-15 min for apps (servers: up to 24 h) | sent with every API call: `Authorization: Bearer ...` |
| **refresh token** | days-weeks, **rotating**, with inactivity + absolute limits, revocable | gets new access tokens without a new login ("stay logged in") |
| **ID token** | for the app only | user info for display; **never** accepted by APIs (the audience check rejects it) |

- **Mobile:** refresh token in the OS secure storage (Keychain / Android Keystore via the provider SDK's
  credentials manager), access token in memory; renew shortly before expiry; biometric unlock optional.
- **Web:** prefer a **backend-for-frontend** (tokens stay on your server; the browser gets an `HttpOnly;
  Secure; SameSite` session cookie). SPA without backend: tokens in memory + refresh token rotation via the
  SDK. Never long-lived tokens in `localStorage`.
- **Logout:** delete local tokens, revoke the refresh token, optionally end the provider session.
- Never log tokens, never print them in tests or CI logs, never commit them.

### A6. Verify every token locally - all four checks

1. **signature** with a key from the issuer's JWKS (fetched once, cached, refreshed on an unknown `kid`)
2. **issuer** (`iss`) equals the configured issuer exactly (watch trailing slashes)
3. **audience** (`aud`) contains *this* API - a valid token for another API must fail
4. **expiry** (`exp`, small clock skew)

Use the framework's verifier configured with issuer + audience; **never write your own JWT parsing**.
No network call per request, no central "/validate" endpoint. Settings without defaults: a service that
doesn't know its issuer must refuse to start.

### A7. Defence in depth

- The **gateway** rejects requests without a valid token early (and does CORS and rate limits).
- **Every service checks again** and decides permissions itself: "inside the network" isn't "trusted".
- **Deny by default:** every path or RPC method not explicitly configured is closed; health and API docs
  are opened explicitly.
- The service that **owns the data** owns its permission rules.

### A8. Permissions and roles

- **Permissions** are named actions on an API (`read:posts`, `write:posts`, `delete:any-post`,
  `moderate:reports`). Defined at the provider, carried in the token, **required** by the code per endpoint.
- **People** get permissions through **roles** (`user`, `moderator`, `admin`); **programs** get them on
  their own client. New users get a default role automatically (or the API treats "authenticated" as
  the base level).
- Changes take effect with the **next token**, which is why access tokens are short-lived.
- **Least privilege:** each caller gets only what it needs.
- The rule sits **next to the endpoint** (annotation per method); dangerous operations get their own
  permission.
- Order of checks: frameworks often validate the request body *before* method-level permission checks
  (400 before 403). Use URL-level rules where that matters.

### A9. Per-object rules live in your own data

"May Anna edit **this** post / see **this** private profile / remove someone from **group 42**?" depends
on data that changes all the time, so it's checked in the service against your DB, using the token's
`sub` as the user id:

```
1. token valid?                        -> provider (A6)              else 401
2. may do this kind of action at all?  -> permission/role (A8)       else 403
3. may do it on THIS object?           -> your data + service code   else 403 / 404
```

Typical data: `posts.author_id`, `follows(follower_id, followee_id, status)`, `profiles.is_private`,
`group_members(group_id, user_id, role)`, `blocks(blocker_id, blocked_id)`. Check visibility in queries
too (a feed must not return posts of private accounts you don't follow). For large sharing graphs use a
relationship-based authorization service (OpenFGA / Auth0 FGA, SpiceDB).

### A10. Service-to-service calls

- A calling service uses **its own identity** (client credentials: client id + secret -> token with
  the service's permissions; in Kubernetes/cloud: workload identity), never the user's token "to get
  through". If the callee must know the user, pass the user id as data, or use token exchange.
- **Cache** the service token until shortly before it expires (one provider request per token lifetime;
  free tiers limit machine tokens).
- Token on every call (HTTP header, gRPC metadata `authorization: Bearer ...`); the callee verifies it
  and the permission per method. No token obtainable -> fail the call cleanly (e.g. 503 + rollback).
- Encrypt traffic between services (TLS/mTLS) outside a local setup.

### A11. Errors, CORS, rate limits, secrets

- 401/403 in the **same error format** as all other errors (RFC 9457 Problem Details), written by the
  security layer; don't echo the exact reason ("signature invalid").
- **CORS** only where browsers call you (gateway/BFF), with an explicit origin list; preflight `OPTIONS`
  carries no token and is answered before authentication. Mobile apps don't need CORS.
- APIs are stateless (no session, no cookie) -> CSRF protection off. A BFF with cookies needs CSRF protection.
- **Rate limits** per user (`sub`) and per IP on expensive and abusable endpoints (posting, search,
  follow, upload); stricter on unauthenticated ones.
- Client secrets only in secret storage (`.env` locally, a secret manager in the cloud), one per service.
  Mobile apps are public clients: **no client secret in the app**.

### A12. Testing without the real provider

| Level | Technique |
|---|---|
| unit / web slice | a mock user with authorities, or a **test decoder** (token string -> claims table) that still runs your real claim -> permission mapping |
| gateway / filters | **sign your own tokens** with a test key and serve its JWKS from a stub: the real verification runs, incl. wrong issuer/audience, expired, forged |
| per-object rules | two test users (owner and stranger): stranger gets 403/404 |
| service-to-service | a fake token endpoint (records grant, client id, audience; counts calls -> caching) + a fake callee recording the auth header |
| end-to-end / CI | a **local fake issuer container** (e.g. mock-oauth2-server) |

Every protected endpoint gets at least: no token -> 401, wrong permission -> 403, right permission ->
success, and someone else's object -> 403/404.

---

## B. Java 21 / Spring Boot + Auth0

### B1. Auth0 setup (dashboard)

| What | Setting |
|---|---|
| Tenants | one per environment (`myapp-dev`, `myapp-prod`), EU/US region as needed |
| **API** | identifier = audience, e.g. `https://api.myapp.example` (can't be changed later), RS256, **Enable RBAC** + **Add Permissions in the Access Token** (claim `permissions`), access token lifetime short for apps; **Allow Offline Access** on (refresh tokens) |
| Permissions | on the API: `read:posts`, `write:posts`, ... |
| **Mobile app** | Application type **Native**; callback/logout URLs = app scheme or universal/app links (`com.example.app://<tenant>/ios/com.example.app/callback`); **Refresh Token Rotation** on, absolute + inactivity expiration; grant types: Authorization Code, Refresh Token |
| Web app (if any) | **Regular Web App** (BFF) or **Single Page App**; allowed origins/callbacks per environment |
| Backend services | one **Machine to Machine** app per calling service, authorized for the API under **Client Access** with only the permissions it needs; a separate M2M app for the **Management API** (e.g. `delete:users`, `read:users`) |
| Connections | Database (Username-Password) with password policy; social: Google, Apple (+ others) |
| Security | Attack Protection (brute force, breached passwords, bot detection), MFA policy, email verification |
| Roles | User Management -> Roles (`user`, `moderator`, `admin`) with API permissions; default role on sign-up via a **Post-Login Action** (`api.authorization` / Management API) or an "authenticated = user" rule in the API |
| Custom claims | via Actions, **namespaced**: `api.accessToken.setCustomClaim("https://myapp.example/username", ...)` |

### B2. Mobile login flow (what the app does)

```
app: Auth0 SDK  webAuth().audience("https://api.myapp.example").scope("openid profile email offline_access")
     -> system browser -> Auth0 Universal Login (login / sign-up / Google / Apple / MFA)
     <- callback with code -> SDK exchanges code + PKCE verifier -> access + refresh + ID token
     -> CredentialsManager stores them (Keychain / Keystore), renews the access token with the refresh token
app -> GET/PUT https://api.myapp.example/... with Authorization: Bearer <access token>
first login -> PUT /me {username, displayName} -> profile created (keyed by sub)
logout -> CredentialsManager.clear() + revoke refresh token (+ clear Auth0 session)
```

SDKs: Auth0.swift, Auth0.Android, react-native-auth0, auth0_flutter. Without `audience` the access token
isn't for your API and every call gets 401.

### B3. Resource server in each service

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OAUTH2_ISSUER_URI}        # https://<tenant>.<region>.auth0.com/  (trailing "/")
          audiences: ${OAUTH2_AUDIENCE}           # the API identifier
          authorities-claim-name: permissions     # Auth0 RBAC claim -> Spring authorities
          authority-prefix: ""                    # authority "read:posts", not "SCOPE_read:posts"
```

```java
@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)                                    // stateless API
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/posts/**", "/me/**").authenticated()               // permission: per method
                .anyRequest().denyAll())                                              // deny by default
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                .authenticationEntryPoint(problemDetails401)                          // same error format
                .accessDeniedHandler(problemDetails403))
            .build();
    }
}

@PreAuthorize("hasAuthority('write:posts')")
@PostMapping("/posts")
ResponseEntity<PostResponse> create(@Valid @RequestBody PostRequest body, @AuthenticationPrincipal Jwt jwt) {
    return created(posts.create(jwt.getSubject(), body));                             // sub = author id
}
```

- Reactive gateway (Spring Cloud Gateway): same starter and properties, `SecurityWebFilterChain` with
  `@EnableWebFluxSecurity`, `pathMatchers("/api/**").authenticated()`, CORS via a `CorsConfigurationSource`
  bean + `.cors(withDefaults())`, `OPTIONS` permitted; the `Authorization` header is forwarded unchanged.
- Rate limiting at the gateway: `RequestRateLimiter` filter with Redis and a `KeyResolver` on the JWT
  `sub` (fallback: client IP).
- Without a `SecurityFilterChain` bean, adding Spring Security locks **every** path (incl. health).

### B4. Per-object rules in Spring

```java
// in the service: the clearest place
Post post = posts.findById(postId).orElseThrow(PostNotFound::new);
if (!post.authorId().equals(callerSub) && !caller.hasAuthority("delete:any-post")) {
    throw new AccessDeniedException("not the author");                               // -> 403 handler
}

// or declaratively with a bean
@PreAuthorize("@postAccess.canEdit(#postId, authentication)")
```

Visibility belongs in queries: `WHERE p.author_id = :me OR a.is_private = false OR EXISTS (follow ...)`.

### B5. Service-to-service with client credentials

```xml
<artifactId>spring-boot-starter-security-oauth2-client</artifactId>
```

```yaml
spring.security.oauth2.client:
  registration:
    media-service:                                   # one registration per callee/audience
      provider: auth0
      client-id: ${FEED_SERVICE_CLIENT_ID}
      client-secret: ${FEED_SERVICE_CLIENT_SECRET}
      authorization-grant-type: client_credentials
      client-authentication-method: client_secret_post
  provider:
    auth0:
      token-uri: ${OAUTH2_TOKEN_URI:${OAUTH2_ISSUER_URI}oauth/token}
```

```java
@Bean
OAuth2AuthorizedClientManager serviceTokens(ClientRegistrationRepository regs, OAuth2AuthorizedClientService clients,
                                           @Value("${OAUTH2_AUDIENCE}") String audience) {
    var tokenClient = new RestClientClientCredentialsTokenResponseClient();
    tokenClient.setParametersCustomizer(p -> p.add("audience", audience));          // Auth0 needs it
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(regs, clients); // works outside requests, caches
    manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
        .clientCredentials(c -> c.accessTokenResponseClient(tokenClient)).build());
    return manager;
}
```

- **HTTP:** `RestClient.builder().requestInterceptor(new OAuth2ClientHttpRequestInterceptor(manager))`
  with the registration id - the header is added and renewed automatically.
- **gRPC client:** a `CallCredentials` that puts `authorization: Bearer <token>` into the metadata
  (`stub.withCallCredentials(...)`); failure to get a token -> `Status.UNAUTHENTICATED` -> domain error.
- **gRPC server:** a `ServerInterceptor` (global) that reads the metadata, calls Spring's `JwtDecoder`,
  checks a method -> permission map (deny unknown methods; health/reflection public) and closes the call
  with `UNAUTHENTICATED` / `PERMISSION_DENIED`. Plain `io.grpc` code survives a change of gRPC starter.
- **Auth0 Management API** (delete user, assign roles): its own registration with audience
  `https://<tenant>/api/v2/`, called only from a backend service.

### B6. Configuration

| Variable | Used by | Value |
|---|---|---|
| `OAUTH2_ISSUER_URI` | every service that accepts tokens | `https://<tenant>.<region>.auth0.com/` |
| `OAUTH2_AUDIENCE` | same | the API identifier |
| `<SERVICE>_CLIENT_ID` / `_SECRET` | each calling service | its own M2M app (secret store only) |
| `OAUTH2_TOKEN_URI` | callers | optional override (tests, fake issuer) |
| `CORS_ALLOWED_ORIGINS` | gateway / BFF | web origins only |

No defaults in `application.yml` for issuer/audience/secrets; the orchestrator fails fast when one is
missing (`${VAR:?}` in compose).

### B7. Testing in Spring

- `@WebMvcTest` + `@Import(SecurityConfig.class)`; `@WithMockUser(authorities = {...})` for tests that
  aren't about security.
- Security tests: a `@TestConfiguration` `JwtDecoder` bean mapping token strings to claims (Boot backs off
  its own decoder) so the real `permissions` -> authority mapping runs; bearer token values must match
  `[A-Za-z0-9-._~+/]+=*` (no `:`).
- Gateway: real signed tokens (Nimbus `RSAKeyGenerator`, `SignedJWT`), JWKS served by a stub HTTP server,
  `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` pointed at it, `issuer-uri`/`audiences` still checked.
- Service tokens: fake token endpoint (JDK `HttpServer`) via `OAUTH2_TOKEN_URI`; assert form params
  (`grant_type`, `client_id`, `audience`) and that a second call reuses the token.
- E2E/CI: `ghcr.io/navikt/mock-oauth2-server` in a compose override file; claims per `client_id` in its
  JSON config; it writes the request host into `iss`, so tests send `Host: <name the services use>`.
- `src/test/resources/application.properties` with placeholder issuer/audience/client values (decoders
  are lazy - nothing is fetched at startup).

### B8. Gotchas (Auth0 + Spring)

- 401 for every token: issuer without trailing `/`, wrong audience, or the token was requested without
  `audience` (then it's not a token for your API).
- 403 although the role/permission is set: old token (get a new one), RBAC "Add Permissions in the
  Access Token" off, or permissions granted under "User Delegated Access" while using client credentials
  (machine tokens use "Client Access").
- `permissions: []` in an M2M token: the app isn't authorized for the API under Client Access.
- Access tokens don't contain `email` by default: add a namespaced claim in an Action or call `/userinfo`.
- Body validation (400) runs before `@PreAuthorize` (403).
- Free tier: limited M2M tokens per month -> cache service tokens.

---

## C. Other stacks, providers and clients

| Concern | Node / TypeScript | Python | Go | .NET |
|---|---|---|---|---|
| Verify JWT | `express-oauth2-jwt-bearer`, `jose` (`createRemoteJWKSet` + `jwtVerify`), NestJS `passport-jwt` | `PyJWT` (`PyJWKClient`), `authlib` | `coreos/go-oidc`, `lestrrat-go/jwx` | `AddJwtBearer(o => { o.Authority; o.Audience; })` |
| Permission per endpoint | `requiredScopes` / Nest guards | FastAPI `Security(..., scopes=[...])` | route middleware | `[Authorize(Policy = "...")]` |
| Client credentials + cache | `openid-client` | `authlib`, `httpx-auth` | `x/oauth2/clientcredentials` | `Duende.AccessTokenManagement` |
| gRPC token | `credentials.createFromMetadataGenerator` | `grpc.metadata_call_credentials` | `credentials.PerRPCCredentials` | `CallCredentials.FromInterceptor` |
| Test tokens | `jose` | PyJWT / jwcrypto | jwx | `Microsoft.IdentityModel.Tokens` |

| Provider | Issuer / audience | Permissions/roles claim | Notes |
|---|---|---|---|
| **Auth0** | `https://<tenant>/` (trailing `/`); `audience` required in token requests | `permissions`, `scope` | Actions for custom claims, Management API |
| **Keycloak** | `https://<host>/realms/<realm>`; audience via client-scope mapper | `realm_access.roles`, `resource_access.<client>.roles` | self-hosted |
| **AWS Cognito** | `https://cognito-idp.<region>.amazonaws.com/<poolId>`; access tokens carry `client_id`, not `aud` | `cognito:groups`, `scope` | Hosted UI, Lambda triggers |
| **Microsoft Entra ID** | `https://login.microsoftonline.com/<tenant>/v2.0` | `roles`, `scp` | app roles |
| **Firebase Auth** | `https://securetoken.google.com/<project>`; audience = project id | custom claims | ID tokens are used as access tokens |

| Client | Login SDK | Token storage |
|---|---|---|
| iOS (Swift) | Auth0.swift / AppAuth-iOS | Keychain (`CredentialsManager`) |
| Android (Kotlin) | Auth0.Android / AppAuth-Android | Keystore-encrypted storage (`SecureCredentialsManager`) |
| React Native / Flutter | react-native-auth0 / auth0_flutter | platform secure storage |
| Web | BFF (Spring `oauth2Login`, Next.js auth libs) or auth0-spa-js | server session / memory |

The pattern stays the same with every provider: hosted login with PKCE, issuer + audience in
configuration, map the provider's permission claim once, require permissions per endpoint, per-object
rules in your own data, service identities for service calls.
