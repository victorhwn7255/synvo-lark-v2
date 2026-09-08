package synvo.billing;

/** Safe categories only: provider bodies and credentials must never become causes/messages. */
public final class BillingException extends RuntimeException {
    public enum Reason { DISABLED, FORBIDDEN, INVALID_REQUEST, BUSY, NOT_FOUND, NOT_READY,
        SOURCE_UNAVAILABLE, SOURCE_INVALID, UNSUPPORTED_CURRENCY, UNSUPPORTED_PRECISION, LIMIT_EXCEEDED,
        AUTHENTICATION, CONFIGURATION, INTERRUPTED, STORAGE_FAILURE }

    private final Reason reason;

    public BillingException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
