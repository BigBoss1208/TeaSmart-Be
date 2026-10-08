package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;

public record OrderItemResponse(Long orderItemId, Long productId, String productName,
        BigDecimal unitPrice, Long quantity, BigDecimal subtotal) {
}
