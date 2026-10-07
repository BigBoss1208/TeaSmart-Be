package vn.teasmart.backend.dto.response;

public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
}
