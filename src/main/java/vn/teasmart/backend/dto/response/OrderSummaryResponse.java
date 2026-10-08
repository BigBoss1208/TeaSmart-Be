package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummaryResponse(Long orderId, String orderCode, String orderStatus,
        BigDecimal totalAmount, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
