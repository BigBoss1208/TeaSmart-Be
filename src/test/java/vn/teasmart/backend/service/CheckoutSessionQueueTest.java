package vn.teasmart.backend.service;

import org.junit.jupiter.api.Test;
import vn.teasmart.backend.enums.CheckoutSessionStatus;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutSessionQueueTest {
    @Test
    void unresolvedVerifyingSessionRemainsInAdminReconciliationQueue() {
        var statuses = CheckoutSessionService.reconciliationQueueStatuses();
        assertTrue(statuses.contains(CheckoutSessionStatus.VERIFYING));
        assertTrue(statuses.contains(CheckoutSessionStatus.RECONCILIATION_REQUIRED));
        assertTrue(statuses.contains(CheckoutSessionStatus.PAID_PENDING_ORDER));
    }
}
