package vn.teasmart.backend.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import vn.teasmart.backend.enums.PaymentStatus;

@Service
public class PaymentReconciliationService {
    private final PaymentService payments;
    private final VnpayQueryClient gateway;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    public PaymentReconciliationService(PaymentService payments, VnpayQueryClient gateway) { this.payments = payments; this.gateway = gateway; }
    // No enclosing transaction: snapshot, network and locked completion are separate operations.
    public void reconcile(Long orderId) {
        if (!running.add(orderId)) return;
        try {
            var snapshot = payments.snapshot(orderId);
            if (snapshot.status() != PaymentStatus.PENDING) return;
            VnpayGateway.Notification result;
            try { result = gateway.query(snapshot); } catch (RuntimeException e) { result = null; }
            if (result == null) payments.unresolved(orderId);
            else {
                String code = payments.accept(result, true);
                if (!"00".equals(code) && !"02".equals(code)) payments.unresolved(orderId);
            }
        } finally { running.remove(orderId); }
    }
    @Scheduled(fixedDelayString = "${teasmart.vnpay.reconciliation-delay-ms:60000}")
    public void reconcileExpired() {
        for (Long id : payments.due()) {
            try { reconcile(id); }
            catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment reconciliation could not complete for order {}", id);
            }
        }
    }
}
