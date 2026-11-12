package dev.asambataiden.iam.provisioning;

import java.util.UUID;

/**
 * Contract for a future target-system integration.
 * No implementation of this interface is registered in production.
 *
 * A successful apply acknowledgement alone NEVER proves effective access.
 * Implementations must independently read back target state.
 */
public interface ProvisioningTargetAdapter {
    record Entitlement(
            UUID tenantId, UUID requestId, UUID employeeId,
            UUID applicationId, String roleKey) {
        public Entitlement {
            if (tenantId == null || requestId == null || employeeId == null
                    || applicationId == null || roleKey == null || roleKey.isBlank()) {
                throw new IllegalArgumentException("Invalid entitlement identity");
            }
        }
    }

    enum ApplyResult { APPLIED, ALREADY_PRESENT }

    /**
     * The idempotency key must identify one provisioning intention across retries.
     * A target implementation must never rely on an ephemeral lease token as the key.
     */
    ApplyResult apply(Entitlement entitlement, String idempotencyKey);

    /**
     * Independent target-system readback. A matching entitlement must be observable
     * before any future external-provisioning completion may be recorded.
     */
    boolean isEffective(Entitlement entitlement);
}
