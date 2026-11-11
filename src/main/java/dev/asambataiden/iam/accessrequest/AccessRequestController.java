package dev.asambataiden.iam.accessrequest;

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
    public record CreateRequest(UUID employeeId, UUID applicationId, String roleKey) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessRequest> create(
            @AuthenticationPrincipal Jwt jwt,
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
