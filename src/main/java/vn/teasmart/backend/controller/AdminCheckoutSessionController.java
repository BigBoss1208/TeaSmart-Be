package vn.teasmart.backend.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.response.CheckoutSessionResponse;
import vn.teasmart.backend.service.CheckoutSessionService;
import vn.teasmart.backend.service.PaymentReconciliationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping("/api/admin/checkout-sessions")
public class AdminCheckoutSessionController {
    private final CheckoutSessionService sessions;
    private final PaymentReconciliationService reconciliation;
    public AdminCheckoutSessionController(CheckoutSessionService sessions, PaymentReconciliationService reconciliation) { this.sessions = sessions; this.reconciliation = reconciliation; }

    @GetMapping("/reconciliation")
    public List<CheckoutSessionResponse> reconciliationQueue() { return sessions.reconciliationQueue(); }

    @PostMapping("/{sessionId}/reconcile")
    public PaymentReconciliationService.ReconciliationResult reconcile(@PathVariable Long sessionId) {
        return reconciliation.reconcileCheckoutSession(sessionId);
    }
}
