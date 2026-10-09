package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;
import vn.teasmart.backend.enums.ReviewStatus;

public record ReviewResponse(Long reviewId, Long productId, Long orderItemId, Integer rating,
        String comment, ReviewStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
