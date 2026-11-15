package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.request.CreateCheckoutSessionRequest;
import vn.teasmart.backend.dto.response.CheckoutPaymentStatusResponse;
import vn.teasmart.backend.dto.response.CheckoutSessionActionResponse;
import vn.teasmart.backend.dto.response.CheckoutSessionResponse;
import vn.teasmart.backend.exception.PaymentException;
import vn.teasmart.backend.service.CheckoutSessionService;
import vn.teasmart.backend.service.PaymentReconciliationService;

@RestController
@RequestMapping("/api/checkout-sessions")
public class CheckoutSessionController {
    private final CheckoutSessionService sessions;
    private final PaymentReconciliationService reconciliation;

    public CheckoutSessionController(CheckoutSessionService sessions, PaymentReconciliationService reconciliation) {
        this.sessions = sessions;
        this.reconciliation = reconciliation;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutSessionResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCheckoutSessionRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return sessions.create(Long.valueOf(jwt.getSubject()), request, key);
    }

    @GetMapping("/active")
    public List<CheckoutSessionResponse> active(@AuthenticationPrincipal Jwt jwt) {
        return sessions.active(Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/payment-status")
    public CheckoutPaymentStatusResponse statusByReference(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9]{1,100}")) throw PaymentException.invalid();
        return sessions.statusByReference(Long.valueOf(jwt.getSubject()), reference);
    }

    @GetMapping("/{sessionId}")
    public CheckoutSessionResponse get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long sessionId) {
        return sessions.get(Long.valueOf(jwt.getSubject()), sessionId);
    }

    @PostMapping("/{sessionId}/payment-attempts/vnpay")
    public CheckoutSessionActionResponse vnpay(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long sessionId,
            @RequestHeader("Idempotency-Key") String key,
            jakarta.servlet.http.HttpServletRequest request) {
        return sessions.startVnpay(Long.valueOf(jwt.getSubject()), sessionId, key, request.getRemoteAddr());
    }

    @PostMapping("/{sessionId}/confirm-cod")
    public CheckoutSessionResponse confirmCod(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long sessionId,
            @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) throw PaymentException.invalid();
        return sessions.confirmCod(Long.valueOf(jwt.getSubject()), sessionId);
    }

    @PostMapping("/{sessionId}/cancel")
    public CheckoutSessionResponse cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long sessionId,
            @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) throw PaymentException.invalid();
        return sessions.cancel(Long.valueOf(jwt.getSubject()), sessionId);
    }

    @PostMapping("/{sessionId}/refresh")
    public CheckoutSessionResponse refresh(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long sessionId,
            @RequestBody(required = false) String body) {
        if (body != null && !body.isBlank()) throw PaymentException.invalid();
        Long userId = Long.valueOf(jwt.getSubject());
        sessions.get(userId, sessionId); // Owner check before any gateway request.
        sessions.latestUnresolvedAttempt(userId, sessionId).ifPresent(reconciliation::reconcileAttempt);
        return sessions.get(userId, sessionId);
    }
}
