package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record ReviewImageResponse(Long imageId, String url, String contentType,
        Long fileSize, LocalDateTime createdAt) {
}
