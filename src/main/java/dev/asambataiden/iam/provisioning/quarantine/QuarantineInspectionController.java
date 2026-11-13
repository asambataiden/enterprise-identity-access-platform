package dev.asambataiden.iam.provisioning.quarantine;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated, read-only quarantine inspection.
 * The tenant is derived exclusively from a locally authorized signed JWT principal.
 * No review writing, retrying, target calls or provisioning transitions.
 */
@RestController
@RequestMapping(path = "/api/v1/provisioning/quarantine",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class QuarantineInspectionController {
    private final QuarantineReviewActorResolver actors;
    private final ProvisioningRecoveryInspector inspector;

    public QuarantineInspectionController(
            QuarantineReviewActorResolver actors,
            ProvisioningRecoveryInspector inspector) {
        this.actors = actors;
        this.inspector = inspector;
    }

    @GetMapping
    public List<ProvisioningRecoveryInspector.Snapshot> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") int limit) {
        var actor = actors.resolve(jwt);
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be 1..100");
        }
        return inspector.quarantined(actor.tenantId(), limit);
    }

    @GetMapping("/{requestId}")
    public ProvisioningRecoveryInspector.Snapshot get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId) {
        var actor = actors.resolve(jwt);
        return inspector.inspect(actor.tenantId(), requestId)
                .filter(s -> "QUARANTINED".equals(s.state()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Quarantined request unavailable"));
    }
}
