package vn.teasmart.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CheckoutSessionSettings {
    private final int sessionTtlMinutes;
    private final int reservationTtlMinutes;
    private final int reconciliationGraceMinutes;

    public CheckoutSessionSettings(
            @Value("${teasmart.checkout.session-ttl-minutes:60}") int sessionTtlMinutes,
            @Value("${teasmart.checkout.reservation-ttl-minutes:60}") int reservationTtlMinutes,
            @Value("${teasmart.checkout.reconciliation-grace-minutes:15}") int reconciliationGraceMinutes) {
        if (sessionTtlMinutes < 10 || sessionTtlMinutes > 1440
                || reservationTtlMinutes < sessionTtlMinutes || reservationTtlMinutes > 2880
                || reconciliationGraceMinutes < 1 || reconciliationGraceMinutes > 120) {
            throw new IllegalArgumentException("Invalid checkout session timing configuration.");
        }
        this.sessionTtlMinutes = sessionTtlMinutes;
        this.reservationTtlMinutes = reservationTtlMinutes;
        this.reconciliationGraceMinutes = reconciliationGraceMinutes;
    }

    public int sessionTtlMinutes() { return sessionTtlMinutes; }
    public int reservationTtlMinutes() { return reservationTtlMinutes; }
    public int reconciliationGraceMinutes() { return reconciliationGraceMinutes; }
}
