package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record PublicReviewResponse(Long reviewId, String authorName, Integer rating,
        String comment, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
