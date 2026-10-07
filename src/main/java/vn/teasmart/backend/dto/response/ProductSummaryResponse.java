package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;

public record ProductSummaryResponse(
        Long productId,
        String name,
        String slug,
        BigDecimal price,
        Long weightGrams,
        String imageUrl,
        String tasteNote,
        Long categoryId,
        String categoryName,
        Long regionId,
        String regionName) {
}
