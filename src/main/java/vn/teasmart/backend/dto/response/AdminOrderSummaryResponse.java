package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminOrderSummaryResponse(Long orderId, String orderCode, Long userId,
        String customerName, String customerEmail, BigDecimal totalAmount, String orderStatus,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
