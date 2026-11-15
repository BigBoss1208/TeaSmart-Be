package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import vn.teasmart.backend.enums.CheckoutSessionStatus;
import vn.teasmart.backend.enums.PaymentAttemptStatus;

public record CheckoutPaymentStatusResponse(Long checkoutSessionId, Long orderId,
        String reference, PaymentAttemptStatus attemptStatus, CheckoutSessionStatus sessionStatus,
        BigDecimal amount, LocalDateTime paidAt, boolean reconciliationRequired) { }
