package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record AdminTeaRegionResponse(
        Long regionId,
        String name,
        String description,
        String location,
        String imageUrl,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
