package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record PaymentCheckoutResponse(OrderResponse order, String paymentUrl,
        LocalDateTime expiresAt, boolean replayed) { }
