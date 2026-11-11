package dev.asambataiden.iam.accessrequest;

/** A tenant-scoped idempotency key was reused with different request terms. */
public final class AccessRequestConflictException extends RuntimeException {
    public AccessRequestConflictException() {
        super("Idempotency key already used for a different request");
    }
}
