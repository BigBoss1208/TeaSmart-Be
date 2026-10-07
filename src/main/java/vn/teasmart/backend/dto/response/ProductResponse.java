package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;

public record ProductResponse(
        Long productId,
        String name,
        String slug,
        String description,
        BigDecimal price,
        Long stockQuantity,
        Long weightGrams,
        String imageUrl,
        String tasteNote,
        Integer strengthLevel,
        Integer astringencyLevel,
        Integer aromaLevel,
        Integer aftertasteLevel,
        Long categoryId,
        String categoryName,
        Long regionId,
        String regionName,
        Long storeId,
        String storeName) {
}
