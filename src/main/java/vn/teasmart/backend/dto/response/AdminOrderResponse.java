package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.enums.PaymentStatus;

public record AdminOrderResponse(Long orderId, String orderCode, Long userId,
        String customerName, String customerEmail, String recipientName,
        String recipientPhone, String shippingAddress, String note, BigDecimal subtotal,
        BigDecimal shippingFee, BigDecimal totalAmount, String orderStatus,
        PaymentMethod paymentMethod, PaymentStatus paymentStatus, List<OrderItemResponse> items,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
