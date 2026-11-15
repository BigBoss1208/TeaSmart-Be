package vn.teasmart.backend.dto.response;
import java.math.BigDecimal;
public record WishlistProductResponse(Long productId, String name, String slug, BigDecimal price,
        Long weightGrams, String imageUrl, String tasteNote, String categoryName,
        Long regionId, String regionName, boolean available) {}
