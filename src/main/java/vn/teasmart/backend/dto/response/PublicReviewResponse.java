package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record PublicReviewResponse(Long reviewId, String authorName, Integer rating,
        String comment, LocalDateTime createdAt, LocalDateTime updatedAt, List<ReviewImageResponse> images) {
}
