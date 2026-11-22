package com.pm.billingservice.grpc;

import billing.BillingServiceGrpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PHASE 3 (step 3.3): NEW - billing's "door check" for gRPC calls.
 *
 * A gRPC ServerInterceptor runs BEFORE the service method, for every call - gRPC's version
 * of a servlet filter. @GrpcGlobalServerInterceptor makes the net.devh starter apply it to
 * all our gRPC services.
 *
 * How it connects to patient-service (step 3.3):
 *   patient-service: BearerTokenCallCredentials puts "authorization: Bearer <token>" in
 *                    the call's metadata (its own token from Auth0, client credentials)
 *   billing (here):  1. read that metadata entry          missing          -> UNAUTHENTICATED
 *                    2. JwtDecoder.decode(token)           bad/expired/
 *                       (signature, iss, aud, exp -        wrong audience   -> UNAUTHENTICATED
 *                        configured in application.yml)
 *                    3. look up the permission this METHOD needs, and check the
 *                       token's "permissions" claim        missing          -> PERMISSION_DENIED
 *                    4. all good -> next.startCall(...) -> BillingGRPC.createBillingAccount
 *
 * UNAUTHENTICATED / PERMISSION_DENIED are gRPC's 401 / 403. patient-service turns any
 * failure into HTTP 503 + rollback, so no patient is saved without a billing account.
 *
 * Written as a plain gRPC interceptor (only io.grpc + Spring's JwtDecoder), not with the
 * net.devh security integration: Phase 4 moves billing to another gRPC library, and this
 * class moves along unchanged.
 */
@GrpcGlobalServerInterceptor
public class BearerTokenInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenInterceptor.class);

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Which permission each gRPC method needs - like @PreAuthorize per REST endpoint.
     * Full method name = "<service>/<method>" (our .proto has no package), here
     * "BillingService/CreateBillingAccount". A method missing here is DENIED (deny by
     * default): a new rpc in the .proto file is closed until someone adds its rule.
     */
    private static final Map<String, String> REQUIRED_PERMISSION = Map.of(
            BillingServiceGrpc.getCreateBillingAccountMethod().getFullMethodName(), "create:billing-accounts");

    /**
     * Infrastructure services that stay open without a token: the standard gRPC health
     * check (used by load balancers / Kubernetes) and server reflection (lets tools like
     * grpcurl or IntelliJ list the API; switched off with GRPC_SERVER_REFLECTION_ENABLED).
     */
    private static final Set<String> PUBLIC_SERVICES = Set.of(
            "grpc.health.v1.Health",
            "grpc.reflection.v1alpha.ServerReflection",
            "grpc.reflection.v1.ServerReflection");

    private final JwtDecoder jwtDecoder;

    public BearerTokenInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers,
                                                       ServerCallHandler<Q, A> next) {
        String method = call.getMethodDescriptor().getFullMethodName();
        if (PUBLIC_SERVICES.contains(call.getMethodDescriptor().getServiceName())) {
            return next.startCall(call, headers);
        }

        // 1. the token from the metadata
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(call, Status.UNAUTHENTICATED, "Missing bearer token", method);
        }

        // 2. verify it (signature, issuer, audience, expiry)
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(authorization.substring("Bearer ".length()));
        } catch (JwtException e) {
            return reject(call, Status.UNAUTHENTICATED, "Invalid or expired token", method);
        }

        // 3. does the caller have the permission THIS method needs?
        String required = REQUIRED_PERMISSION.get(method);
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (required == null || permissions == null || !permissions.contains(required)) {
            return reject(call, Status.PERMISSION_DENIED, "Missing permission for this operation", method);
        }

        // 4. allowed. "sub" of a client-credentials token = the CALLING SERVICE's client id
        // (Auth0 adds "@clients") - an ID, no personal data, so it may be logged.
        log.debug("gRPC {} allowed for caller {}", method, jwt.getSubject());
        return next.startCall(call, headers);
    }

    /** Ends the call with a status; the service method never runs. Logs no token content. */
    private static <Q, A> ServerCall.Listener<Q> reject(ServerCall<Q, A> call, Status status,
                                                       String description, String method) {
        log.warn("gRPC {} rejected: {} ({})", method, status.getCode(), description);
        call.close(status.withDescription(description), new Metadata());
        return new ServerCall.Listener<>() { };
    }
}
