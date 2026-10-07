package vn.teasmart.backend.dto.response;

public record CategoryResponse(
        Long categoryId,
        String name,
        String slug,
        String description) {
}
