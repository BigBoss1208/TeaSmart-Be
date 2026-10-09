package vn.teasmart.backend.controller;

import jakarta.validation.constraints.Positive;
import java.util.Map;
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
    public PaymentController(PaymentService service, VnpayGateway gateway, PaymentReconciliationService reconciliation) {
        this.service = service; this.gateway = gateway; this.reconciliation = reconciliation;
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
        try { code = service.accept(gateway.verifyIpn(params), false); }
        catch (VnpayGateway.InvalidNotification e) { code = e.code(); }
        catch (RuntimeException e) { code = "99"; } // Transaction has rolled back before acknowledging failure.
        String message = switch (code) {
            case "00" -> "Confirm Success"; case "02" -> "Order already confirmed";
            case "01" -> "Order not found"; case "04" -> "Invalid amount";
            case "97" -> "Invalid signature"; default -> "Unable to process notification";
        };
        return Map.of("RspCode", code, "Message", message);
    }
}
