package dev.asambataiden.iam.provisioning.quarantine;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

    @Operation(summary = "List tenant-local quarantined work snapshots",
            description = "Read-only. Requires provisioning:review scope and an ACTIVE "
                    + "tenant-local employee with PROVISIONING_QUARANTINE_REVIEW permission. "
                    + "No retry, repair, target action or grant is possible.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bounded diagnostic snapshot list",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = ProvisioningRecoveryInspector.Snapshot.class)))),
            @ApiResponse(responseCode = "400", description = "Limit outside 1..100", content = @Content),
            @ApiResponse(responseCode = "401", description = "Missing/invalid bearer", content = @Content),
            @ApiResponse(responseCode = "403", description = "Missing scope or local permission", content = @Content)
    })
    @GetMapping
    public List<ProvisioningRecoveryInspector.Snapshot> list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Result limit (default 20; allowed 1..100)",
                    schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(defaultValue = "20") int limit) {
        var actor = actors.resolve(jwt);
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be 1..100");
        }
        return inspector.quarantined(actor.tenantId(), limit);
    }

    @Operation(summary = "Inspect one tenant-local quarantined work item",
            description = "Read-only tenant-isolated diagnosis; missing, foreign or non-quarantined "
                    + "work returns a generic 404. Mock observation evidence is not a real entitlement.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quarantine snapshot",
                    content = @Content(schema = @Schema(
                            implementation = ProvisioningRecoveryInspector.Snapshot.class))),
            @ApiResponse(responseCode = "401", description = "Missing/invalid bearer", content = @Content),
            @ApiResponse(responseCode = "403", description = "Missing scope or local permission", content = @Content),
            @ApiResponse(responseCode = "404", description = "Quarantined item unavailable", content = @Content)
    })
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
