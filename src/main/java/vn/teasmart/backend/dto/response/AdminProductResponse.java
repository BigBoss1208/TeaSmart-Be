package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminProductResponse(
        Long productId, String name, String slug, String description,
        BigDecimal price, Long stockQuantity, Long weightGrams,
        String imageUrl, String tasteNote,
        Integer strengthLevel, Integer astringencyLevel, Integer aromaLevel, Integer aftertasteLevel,
        String status, LocalDateTime createdAt, LocalDateTime updatedAt,
        Long categoryId, String categoryName, String categoryStatus,
        Long regionId, String regionName, String regionStatus,
        Long storeId, String storeName, String storeStatus) {
}
