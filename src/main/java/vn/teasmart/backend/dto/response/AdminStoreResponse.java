package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record AdminStoreResponse(
        Long storeId,
        String name,
        String description,
        String address,
        String phone,
        String email,
        String logoUrl,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
