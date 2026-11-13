package dev.asambataiden.iam.provisioning.outbox;

import dev.asambataiden.iam.provisioning.inbox.ProvisioningInboxConsumer;
import java.util.Objects;

/**
 * Explicit lab-only transport adapter. Not a broker listener or Spring bean.
 * Trust is limited to the in-process OutboxDeliveryService caller.
 */
public final class InProcessInboxPublisher implements OutboxEventPublisher {
    private final ProvisioningInboxConsumer inbox;

    public InProcessInboxPublisher(ProvisioningInboxConsumer inbox) {
        this.inbox = Objects.requireNonNull(inbox);
    }

    @Override
    public void publish(OutboxDeliveryService.Delivery event) {
        Objects.requireNonNull(event, "event");
        inbox.consume(new ProvisioningInboxConsumer.Message(
                event.eventId(), event.tenantId(), event.requestId(),
                event.eventType(), event.schemaVersion(), event.payload()));
        // APPLIED and DUPLICATE both acknowledge successful local processing.
        // Consumer transaction commits before the relay marks outbox DELIVERED.
    }
}
