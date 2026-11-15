package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record UserResponse(
        Long userId,
        String fullName,
        String email,
        String phone,
        String avatarUrl,
        String role,
        LocalDateTime createdAt, boolean sellerEnabled, java.util.List<String> authorities) {
    public UserResponse(Long userId, String fullName, String email, String phone, String avatarUrl, String role, LocalDateTime createdAt) {
        this(userId, fullName, email, phone, avatarUrl, role, createdAt, false, java.util.List.of(role));
    }
}
