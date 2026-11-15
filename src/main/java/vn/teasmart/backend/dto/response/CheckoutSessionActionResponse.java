package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record CheckoutSessionActionResponse(CheckoutSessionResponse session, String paymentUrl,
        String attemptReference, LocalDateTime attemptExpiresAt, boolean replayed) { }
