package dev.asambataiden.iam.accessrequest;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccessRequest(
        UUID id,
        UUID tenantId,
        UUID requesterId,
        UUID employeeId,
        UUID applicationId,
        String roleKey,
        String idempotencyKey,
        String status,
        OffsetDateTime createdAt
) {}
