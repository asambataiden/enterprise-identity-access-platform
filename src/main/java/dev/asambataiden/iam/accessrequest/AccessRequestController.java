package dev.asambataiden.iam.accessrequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/v1/access-requests", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccessRequestController {
    private final RequestActorResolver actors;
    private final AccessRequestService service;

    public AccessRequestController(RequestActorResolver actors, AccessRequestService service) {
        this.actors = actors;
        this.service = service;
    }

    // Client must not supply tenantId, requesterId, or a privileged identity.
    @Schema(name = "CreateAccessRequest",
            description = "Tenant and requester are resolved from a trusted bearer principal, not from JSON.")
    public record CreateRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID employeeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID applicationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64)
            String roleKey) {}

    @Operation(summary = "Create or idempotently replay an access request",
            description = "Requires access:request scope plus an ACTIVE tenant-local employee with "
                    + "ACCESS_REQUEST_CREATE. The requester's tenant/identity are server-derived. "
                    + "Equivalent replay returns the existing row with HTTP 201.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created or equivalent idempotent replay",
                    content = @Content(schema = @Schema(implementation = AccessRequest.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing/invalid bearer", content = @Content),
            @ApiResponse(responseCode = "403", description = "Insufficient scope/local permission", content = @Content),
            @ApiResponse(responseCode = "404", description = "Requested tenant-local resource unavailable", content = @Content),
            @ApiResponse(responseCode = "409", description = "Same idempotency key with changed intent", content = @Content)
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessRequest> create(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Tenant-scoped stable key, 1-128 characters, no surrounding whitespace",
                    required = true, schema = @Schema(minLength = 1, maxLength = 128))
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateRequest request
    ) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.employeeId() == null || request.applicationId() == null
                || request.roleKey() == null || request.roleKey().isBlank()
                || request.roleKey().length() > 64 || !request.roleKey().equals(request.roleKey().trim())
                || key.isBlank() || key.length() > 128 || !key.equals(key.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request");
        }
        RequestActorResolver.Actor actor = actors.resolve(jwt);
        try {
            AccessRequest result = service.create(actor.tenantId(), actor.employeeId(),
                    request.employeeId(), request.applicationId(), request.roleKey(), key);
            return ResponseEntity.created(URI.create("/api/v1/access-requests/" + result.id())).body(result);
        } catch (AccessRequestConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage());
        } catch (IllegalArgumentException invalid) {
            // Do not reveal whether a foreign employee, application or role exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Requested resource not available");
        }
    }
}
