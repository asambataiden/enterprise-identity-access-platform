package dev.asambataiden.iam.provisioning.outbox;

import java.util.Objects;

/** Explicitly invoked relay. No background scheduler or production publisher is installed. */
public final class OutboxRelay {
    private final OutboxDeliveryService deliveries;
    private final OutboxEventPublisher publisher;

    public OutboxRelay(OutboxDeliveryService deliveries, OutboxEventPublisher publisher) {
        this.deliveries = Objects.requireNonNull(deliveries);
        this.publisher = Objects.requireNonNull(publisher);
    }

    /** Processes at most one event; database transactions never span publish(). */
    public boolean runOnce() {
        var optional = deliveries.claim(30);
        if (optional.isEmpty()) {
            return false;
        }
        var event = optional.orElseThrow();
        try {
            publisher.publish(event);
        } catch (RuntimeException failure) {
            // An exception after remote publish may leave an uncertain outcome.
            // The next attempt MUST reuse eventId; no secret/exception is persisted.
            int delay = Math.min(300, 1 << Math.min(event.attempt() - 1, 8));
            if (!deliveries.fail(event, OutboxDeliveryService.FailureCode.PUBLISH_UNAVAILABLE, delay)) {
                throw new IllegalStateException("Delivery lease lost while recording failure");
            }
            return true;
        }
        if (!deliveries.delivered(event)) {
            throw new IllegalStateException("Delivery lease lost after publish; duplicate delivery is possible");
        }
        return true;
    }
}
