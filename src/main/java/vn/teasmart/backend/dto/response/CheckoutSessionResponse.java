package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import vn.teasmart.backend.enums.CheckoutSessionStatus;
import vn.teasmart.backend.enums.PaymentAttemptStatus;
import vn.teasmart.backend.enums.ReservationStatus;

public record CheckoutSessionResponse(Long checkoutSessionId, String sessionCode,
        CheckoutSessionStatus status, ReservationStatus reservationStatus,
        LocalDateTime expiresAt, LocalDateTime reservationExpiresAt,
        String recipientName, String recipientPhone, String shippingAddress, String note,
        BigDecimal subtotal, BigDecimal shippingFee, BigDecimal totalAmount,
        PaymentAttemptStatus latestAttemptStatus, String latestReference,
        Integer latestAttemptNumber, boolean reconciliationRequired, Long orderId,
        List<Item> items, Long shopId, String shopName) {
    public record Item(Long productId, String productName, BigDecimal unitPrice, Long quantity, BigDecimal subtotal) { }
}
