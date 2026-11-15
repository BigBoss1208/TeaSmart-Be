package vn.teasmart.backend.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import vn.teasmart.backend.enums.PaymentStatus;

@Service
public class PaymentReconciliationService {
    private final PaymentService payments;
    private final VnpayQueryClient gateway;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final CheckoutSessionService sessions;
    private final vn.teasmart.backend.repository.PaymentAttemptRepository attempts;
    private final VnpayCheckoutAudit audit;
    private final Set<Long> attemptsRunning = ConcurrentHashMap.newKeySet();
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentReconciliationService(PaymentService payments, VnpayQueryClient gateway,
            CheckoutSessionService sessions, vn.teasmart.backend.repository.PaymentAttemptRepository attempts, VnpayCheckoutAudit audit) {
        this.payments = payments; this.gateway = gateway; this.sessions = sessions; this.attempts = attempts;
        this.audit = audit;
    }
    public PaymentReconciliationService(PaymentService payments, VnpayQueryClient gateway) {
        this(payments, gateway, null, null, null);
    }
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
    public void reconcileExpired() {
        for (Long id : payments.due()) {
            try { reconcile(id); }
            catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment reconciliation could not complete for order {}", id);
            }
        }
        if (attempts == null || sessions == null) return;
        for (Long attemptId : attempts.findReconciliationCandidates(PaymentTime.now(),
                PaymentTime.now().minusMinutes(6), java.util.List.of(vn.teasmart.backend.enums.PaymentAttemptStatus.PENDING,
                    vn.teasmart.backend.enums.PaymentAttemptStatus.UNKNOWN),
                org.springframework.data.domain.PageRequest.of(0, 20))) {
            try { reconcileAttempt(attemptId); }
            catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Payment attempt reconciliation could not complete for attempt {}", attemptId);
            }
        }
        for (Long sessionId : sessions.expiredReservationCandidates()) {
            try { sessions.expireReservation(sessionId); }
            catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Checkout reservation could not be resolved for session {}", sessionId);
            }
        }
    }

    /** Gateway network calls remain outside database transactions. */
    public void reconcileAttempt(Long attemptId) {
        reconcileAttemptDiagnostic(attemptId);
    }

    private ReconciliationResult reconcileAttemptDiagnostic(Long attemptId) {
        if (!attemptsRunning.add(attemptId)) return new ReconciliationResult("IN_FLIGHT", attemptId,
                "PENDING", "NOT_REQUESTED", false, null, 0, null, null);
        try {
            CheckoutSessionService.AttemptSnapshot snapshot = sessions.snapshot(attemptId);
            VnpayQueryClient.QueryResult query;
            try { query = gateway.queryAttemptDiagnostic(snapshot); }
            catch (RuntimeException e) { query = new VnpayQueryClient.QueryResult("CLIENT_ERROR", false, null, 0, null, null); }
            String processing = "PROCESSING_ERROR";
            try {
                ReconciliationResult result = applyQueryResult(attemptId, query);
                processing = result.outcome();
                return result;
            } finally {
                if (audit != null) audit.query(snapshot.reference(), query, processing);
            }
        } finally { attemptsRunning.remove(attemptId); }
    }

    private ReconciliationResult applyQueryResult(Long attemptId, VnpayQueryClient.QueryResult query) {
        VnpayGateway.Notification result = query.notification();
        if (result == null) {
            sessions.markAttemptUnresolved(attemptId);
            return result(attemptId, query, "UNRESOLVED_RETAINED");
        }
        CheckoutSessionService.GatewayAcceptance accepted = sessions.acceptAttempt(result, true);
        if (!accepted.matched()) {
            sessions.markAttemptUnresolved(attemptId);
            return result(attemptId, query, "VERIFIED_RESPONSE_MISMATCH");
        }
        if ("04".equals(accepted.responseCode())) return result(attemptId, query, "VERIFIED_AMOUNT_OR_REFERENCE_MISMATCH");
        if (!"00".equals(accepted.responseCode()) && !"02".equals(accepted.responseCode()))
            return result(attemptId, query, "PROCESSING_CODE_" + accepted.responseCode());
        if (accepted.finalizeSessionId() != null) {
            try { sessions.finalizePaidSession(accepted.finalizeSessionId()); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Verified payment is durable; checkout finalization will retry for session {}",
                        accepted.finalizeSessionId());
                return result(attemptId, query, "VERIFIED_PAYMENT_FINALIZATION_PENDING");
            }
            return result(attemptId, query, "VERIFIED_SUCCESS_ORDER_FINALIZED");
        }
        String currentStatus = attemptStatus(attemptId);
        if ("FAILED_FINAL".equals(currentStatus)) return result(attemptId, query, "VERIFIED_FINAL_FAILURE");
        if ("SUCCEEDED".equals(currentStatus)) return result(attemptId, query, "VERIFIED_SUCCESS_RECORDED");
        return result(attemptId, query, result.success() ? "VERIFIED_SUCCESS_NOT_FINALIZED" : "VERIFIED_RESPONSE_NOT_FINAL");
    }

    private ReconciliationResult result(Long attemptId, VnpayQueryClient.QueryResult query, String outcome) {
        return new ReconciliationResult(outcome, attemptId, attemptStatus(attemptId), query.outcome(), query.requestSent(),
                query.httpStatus(), query.elapsedMs(), query.gatewayResponseCode(), query.gatewayTransactionStatus());
    }

    private String attemptStatus(Long attemptId) {
        return attempts.findById(attemptId).map(a -> a.getStatus().name()).orElse("NOT_FOUND");
    }

    public ReconciliationResult reconcileCheckoutSession(Long sessionId) {
        var unresolvedIds = attempts.findUnresolvedAttemptIds(sessionId, java.util.List.of(
                vn.teasmart.backend.enums.PaymentAttemptStatus.PENDING,
                vn.teasmart.backend.enums.PaymentAttemptStatus.UNKNOWN));
        if (!unresolvedIds.isEmpty()) {
            Long attemptId = unresolvedIds.get(unresolvedIds.size() - 1);
            if (attemptsRunning.contains(attemptId)) return new ReconciliationResult("IN_FLIGHT", attemptId,
                    "PENDING", "NOT_REQUESTED", false, null, 0, null, null);
            return reconcileAttemptDiagnostic(attemptId);
        }
        var candidates = sessions.reconciliationQueue().stream().filter(s -> s.checkoutSessionId().equals(sessionId)).findFirst();
        if (candidates.isPresent() && candidates.get().status() == vn.teasmart.backend.enums.CheckoutSessionStatus.PAID_PENDING_ORDER) {
            try { sessions.finalizePaidSession(sessionId); return new ReconciliationResult("VERIFIED_PAYMENT_ORDER_FINALIZED", null, null, "NOT_REQUESTED", false, null, 0, null, null); }
            catch (RuntimeException e) { return new ReconciliationResult("VERIFIED_PAYMENT_FINALIZATION_PENDING", null, null, "NOT_REQUESTED", false, null, 0, null, null); }
        }
        return new ReconciliationResult("NO_UNRESOLVED_ATTEMPT", null, null, "NOT_REQUESTED", false, null, 0, null, null);
    }

    public record ReconciliationResult(String outcome, Long paymentAttemptId, String attemptStatus,
            String queryOutcome, boolean requestSent, Integer httpStatus, long elapsedMs,
            String gatewayResponseCode, String gatewayTransactionStatus) {}
}
