package dev.asambataiden.iam.accessrequest;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Approval records only. Never provisions entitlements or accepts a caller-supplied approver. */
@RestController
@RequestMapping(path = "/api/v1/access-requests", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApprovalDecisionController {
    private final DecisionActorResolver actors;
    private final ApprovalDecisionService service;

    public ApprovalDecisionController(DecisionActorResolver actors, ApprovalDecisionService service) {
        this.actors = actors;
        this.service = service;
    }

    public record DecisionRequest(ApprovalDecisionService.Decision decision) {}
    public record DecisionResponse(UUID requestId, String decision) {}

    @PostMapping(path = "/{requestId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DecisionResponse> decide(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId,
            @RequestBody DecisionRequest body) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (body == null || body.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A decision is required");
        }
        var actor = actors.resolve(jwt);
        try {
            service.decide(actor.tenantId(), actor.employeeId(), requestId, body.decision());
        } catch (SecurityException forbidden) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approval not permitted");
        } catch (IllegalArgumentException notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not available");
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request already decided");
        }
        return ResponseEntity.ok(new DecisionResponse(requestId, body.decision().name()));
    }
}
