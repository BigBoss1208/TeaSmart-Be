package vn.teasmart.backend.controller;

import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.MultiValueMap;
import vn.teasmart.backend.dto.response.PaymentResponse;
import vn.teasmart.backend.service.*;
import vn.teasmart.backend.exception.PaymentException;

@RestController
public class PaymentController {
    private final PaymentService service;
    private final VnpayGateway gateway;
    private final PaymentReconciliationService reconciliation;
    private final CheckoutSessionService checkoutSessions;
    private final VnpayCheckoutAudit audit;
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentController(PaymentService service, VnpayGateway gateway, PaymentReconciliationService reconciliation,
            CheckoutSessionService checkoutSessions, VnpayCheckoutAudit audit) {
        this.service = service; this.gateway = gateway; this.reconciliation = reconciliation;
        this.checkoutSessions = checkoutSessions;
        this.audit = audit;
    }
    public PaymentController(PaymentService service, VnpayGateway gateway, PaymentReconciliationService reconciliation) {
        this(service, gateway, reconciliation, null, null);
    }
    @GetMapping("/api/orders/{orderId}/payment")
    public PaymentResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long orderId) {
        return service.customerStatus(Long.valueOf(jwt.getSubject()), orderId);
    }
    @GetMapping("/api/orders/payment-status")
    public PaymentResponse byReference(@AuthenticationPrincipal Jwt jwt, @RequestParam String reference) {
        if (!reference.matches("[A-Za-z0-9]{1,100}")) throw PaymentException.invalid();
        return service.customerStatusByReference(Long.valueOf(jwt.getSubject()), reference);
    }
    @GetMapping("/api/admin/orders/{orderId}/payment")
    public PaymentResponse adminStatus(@PathVariable @Positive Long orderId) { return service.adminStatus(orderId); }
    @PatchMapping("/api/admin/orders/{orderId}/payment/confirm-cod")
    public PaymentResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long orderId,
            @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) throw PaymentException.invalid();
        return service.confirmCod(Long.valueOf(jwt.getSubject()), orderId);
    }
    @PostMapping("/api/admin/orders/{orderId}/payment/reconcile")
    public PaymentResponse reconcile(@PathVariable @Positive Long orderId, @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) throw PaymentException.invalid();
        reconciliation.reconcile(orderId);
        return service.adminStatus(orderId);
    }
    @GetMapping("/api/payments/vnpay/ipn")
    public Map<String, String> ipn(@RequestParam MultiValueMap<String, String> params) {
        String code;
        try { code = processCallback(VnpayCheckoutAudit.Source.IPN, params); }
        catch (VnpayGateway.InvalidNotification e) { code = e.code(); }
        catch (RuntimeException e) { code = "99"; } // Transaction has rolled back before acknowledging failure.
        String message = switch (code) {
            case "00" -> "Confirm Success"; case "02" -> "Order already confirmed";
            case "01" -> "Order not found"; case "04" -> "Invalid amount";
            case "97" -> "Invalid signature"; default -> "Unable to process notification";
        };
        return Map.of("RspCode", code, "Message", message);
    }

    @PostMapping("/api/payments/vnpay/return")
    public Map<String, Object> browserReturn(@AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, String> params) {
        if (params == null || params.size() > 20) {
            recordCallback(VnpayCheckoutAudit.Source.RETURN, null, "MALFORMED_NOTIFICATION", "REJECTED");
            throw PaymentException.invalid();
        }
        org.springframework.util.LinkedMultiValueMap<String, String> values = new org.springframework.util.LinkedMultiValueMap<>();
        params.forEach((key, value) -> values.add(key, value));
        String code;
        try { code = processCallback(VnpayCheckoutAudit.Source.RETURN, values); }
        catch (VnpayGateway.InvalidNotification invalid) { throw PaymentException.invalid(); }
        if (!"00".equals(code) && !"02".equals(code)) throw PaymentException.conflict("PAYMENT_RETURN_NOT_ACCEPTED");
        return Map.of("accepted", true, "reference", values.getFirst("vnp_TxnRef"));
    }

    private String processCallback(VnpayCheckoutAudit.Source source, MultiValueMap<String, String> values) {
        VnpayGateway.Notification notification = null;
        String verification = "VERIFICATION_ERROR";
        String outcome = "PROCESSING_ERROR";
        try {
            notification = source == VnpayCheckoutAudit.Source.IPN ? gateway.verifyIpn(values) : gateway.verifyReturn(values);
            verification = "SIGNATURE_AND_MERCHANT_VERIFIED";
            String code = acceptGatewayResult(notification, false);
            outcome = "PROCESSING_CODE_" + code;
            return code;
        } catch (VnpayGateway.InvalidNotification e) {
            verification = e.verificationOutcome();
            outcome = "REJECTED_CODE_" + e.code();
            throw e;
        } finally {
            recordCallback(source, notification, verification, outcome);
        }
    }

    private void recordCallback(VnpayCheckoutAudit.Source source, VnpayGateway.Notification notification,
            String verification, String outcome) {
        if (audit != null) audit.callback(source, notification, verification, outcome);
    }

    private String acceptGatewayResult(VnpayGateway.Notification notification, boolean reconciliationResult) {
        CheckoutSessionService.GatewayAcceptance accepted = checkoutSessions == null
                ? CheckoutSessionService.GatewayAcceptance.unmatched()
                : checkoutSessions.acceptAttempt(notification, reconciliationResult);
        if (!accepted.matched()) return service.accept(notification, reconciliationResult);
        if (accepted.finalizeSessionId() != null) {
            try { checkoutSessions.finalizePaidSession(accepted.finalizeSessionId()); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Verified payment is durable; checkout finalization will retry for session {}",
                        accepted.finalizeSessionId());
            }
        }
        return accepted.responseCode();
    }
}
