package dev.asambataiden.iam.provisioning;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProvisioningTargetAdapterContractTest {
    /**
     * Isolated fake target with separate apply acknowledgements and readback.
     * Not a Spring bean, Keycloak integration or production connector.
     */
    private static final class FakeTarget implements ProvisioningTargetAdapter {
        private final Map<String, Entitlement> accepted = new HashMap<>();
        private final Set<Entitlement> effective = new HashSet<>();
        private boolean acknowledgeWithoutApplying;

        @Override
        public ApplyResult apply(Entitlement entitlement, String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Missing target idempotency key");
            }
            Entitlement prior = accepted.putIfAbsent(key, entitlement);
            if (prior != null) {
                if (!prior.equals(entitlement)) {
                    throw new IllegalStateException("Idempotency key reused for different entitlement");
                }
                return ApplyResult.ALREADY_PRESENT;
            }
            if (!acknowledgeWithoutApplying) {
                effective.add(entitlement);
            }
            return ApplyResult.APPLIED;
        }

        @Override
        public boolean isEffective(Entitlement entitlement) {
            return effective.contains(entitlement);
        }
    }

    @Test
    void replayIsIdempotentAndReadbackIsIndependent() {
        var target = new FakeTarget();
        UUID tenant = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var entitlement = new ProvisioningTargetAdapter.Entitlement(
                tenant, request, UUID.randomUUID(), UUID.randomUUID(), "READER");
        String key = ProvisioningAttemptJournal.idempotencyKey(tenant, request);
        assertFalse(target.isEffective(entitlement));
        assertEquals(ProvisioningTargetAdapter.ApplyResult.APPLIED, target.apply(entitlement, key));
        assertTrue(target.isEffective(entitlement));
        assertEquals(ProvisioningTargetAdapter.ApplyResult.ALREADY_PRESENT, target.apply(entitlement, key));
    }

    @Test
    void acknowledgementIsNotEvidenceOfActualEntitlement() {
        var target = new FakeTarget();
        target.acknowledgeWithoutApplying = true;
        UUID tenant = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        var entitlement = new ProvisioningTargetAdapter.Entitlement(
                tenant, request, UUID.randomUUID(), UUID.randomUUID(), "READER");
        assertEquals(ProvisioningTargetAdapter.ApplyResult.APPLIED,
                target.apply(entitlement, ProvisioningAttemptJournal.idempotencyKey(tenant, request)));
        assertFalse(target.isEffective(entitlement));
    }

    @Test
    void targetRejectsCrossEntitlementIdempotencyCollision() {
        var target = new FakeTarget();
        UUID tenant = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String key = ProvisioningAttemptJournal.idempotencyKey(tenant, request);
        var first = new ProvisioningTargetAdapter.Entitlement(
                tenant, request, UUID.randomUUID(), UUID.randomUUID(), "READER");
        var second = new ProvisioningTargetAdapter.Entitlement(
                tenant, request, UUID.randomUUID(), UUID.randomUUID(), "ADMIN");
        target.apply(first, key);
        assertThrows(IllegalStateException.class, () -> target.apply(second, key));
        assertFalse(target.isEffective(second));
    }
}
