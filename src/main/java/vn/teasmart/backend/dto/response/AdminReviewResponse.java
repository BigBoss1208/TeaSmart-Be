package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;
import vn.teasmart.backend.enums.ReviewStatus;

public record AdminReviewResponse(Long reviewId, Long productId, String productName,
        Long userId, String authorName, Long orderItemId, Integer rating, String comment,
        ReviewStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
