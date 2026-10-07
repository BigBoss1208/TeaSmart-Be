package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record UserResponse(
        Long userId,
        String fullName,
        String email,
        String phone,
        String avatarUrl,
        String role,
        LocalDateTime createdAt) {
}
