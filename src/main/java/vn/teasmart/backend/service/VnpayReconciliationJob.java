package vn.teasmart.backend.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "teasmart.vnpay.reconciliation-enabled", havingValue = "true", matchIfMissing = true)
public class VnpayReconciliationJob {
    private final PaymentReconciliationService reconciliation;

    public VnpayReconciliationJob(PaymentReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${teasmart.vnpay.reconciliation-delay-ms:60000}")
    public void reconcileExpired() {
        reconciliation.reconcileExpired();
    }
}
