package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        Long cartId, List<CartItemResponse> items, Long totalItems, BigDecimal totalAmount) {
}
