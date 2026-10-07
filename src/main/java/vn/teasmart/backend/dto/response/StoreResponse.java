package vn.teasmart.backend.dto.response;

public record StoreResponse(
        Long storeId,
        String name,
        String description,
        String address,
        String phone,
        String email,
        String logoUrl) {
}
