package dev.asambataiden.iam.accessrequest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(name = "AccessRequest", description = "Stored tenant-local governance request; no real entitlement is implied.")
public record AccessRequest(
        UUID id,
        UUID tenantId,
        UUID requesterId,
        UUID employeeId,
        UUID applicationId,
        String roleKey,
        String idempotencyKey,
        @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED"})
        String status,
        OffsetDateTime createdAt
) {}
