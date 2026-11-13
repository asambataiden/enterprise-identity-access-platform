package dev.asambataiden.iam.provisioning;

/** A publisher must be idempotency-aware: delivery can be attempted repeatedly. */
@FunctionalInterface
public interface OutboxEventPublisher {
    /** Publish the immutable event identity and payload; return only after acknowledgement. */
    void publish(OutboxDeliveryService.Delivery event);
}
