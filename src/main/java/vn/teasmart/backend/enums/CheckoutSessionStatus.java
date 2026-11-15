package vn.teasmart.backend.enums;

public enum CheckoutSessionStatus {
    READY,
    AWAITING_PAYMENT,
    VERIFYING,
    PAID_PENDING_ORDER,
    COMPLETED,
    EXPIRED,
    CANCELLED,
    RECONCILIATION_REQUIRED,
    ADMIN_QUARANTINED
}
