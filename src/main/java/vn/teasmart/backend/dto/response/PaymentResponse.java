package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import vn.teasmart.backend.entity.Payment;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.enums.PaymentStatus;

public record PaymentResponse(Long orderId, PaymentMethod paymentMethod, String gateway,
        PaymentStatus paymentStatus, BigDecimal amount, LocalDateTime paidAt,
        LocalDateTime expiresAt, boolean reconciliationRequired, LocalDateTime lastReconciliationAt) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getOrder().getOrderId(), p.getPaymentMethod(), p.getGateway(),
                p.getPaymentStatus(), p.getAmount(), p.getPaidAt(), p.getExpiresAt(),
                p.isReconciliationRequired(), p.getLastReconciliationAt());
    }
}
