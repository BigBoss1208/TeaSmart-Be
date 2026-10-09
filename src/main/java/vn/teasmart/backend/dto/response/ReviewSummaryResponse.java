package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;

public record ReviewSummaryResponse(Long productId, long totalReviews, BigDecimal averageRating) {
}
