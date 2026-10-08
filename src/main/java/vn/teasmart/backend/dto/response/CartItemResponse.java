package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;

public record CartItemResponse(
        Long cartItemId, Long productId, String name, String slug, String imageUrl,
        BigDecimal unitPrice, Long quantity, BigDecimal subtotal, Long stockQuantity,
        boolean available, String availabilityReason) {
}
