package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record AdminCategoryResponse(
        Long categoryId,
        String name,
        String slug,
        String description,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
