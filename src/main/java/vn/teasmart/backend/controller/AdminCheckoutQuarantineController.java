package vn.teasmart.backend.controller;

import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.CheckoutSessionService;
import vn.teasmart.backend.dto.response.CheckoutSessionResponse;

/** Disabled unless an explicitly approved administrative maintenance window enables it. */
@RestController
@RequestMapping("/api/admin/checkout-quarantine")
@ConditionalOnProperty(name="teasmart.checkout.quarantine-enabled", havingValue="true")
public class AdminCheckoutQuarantineController {
    private final CheckoutSessionService service;
    public AdminCheckoutQuarantineController(CheckoutSessionService service) { this.service = service; }
    public record Request(Long ownerId, LocalDateTime cutoff, String reason) {}
    @PostMapping("/{sessionId}")
    public CheckoutSessionResponse quarantine(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sessionId,
            @RequestBody Request request) {
        return service.quarantineHistorical(Long.valueOf(jwt.getSubject()), request.ownerId(), sessionId,
                request.cutoff(), request.reason());
    }
}
