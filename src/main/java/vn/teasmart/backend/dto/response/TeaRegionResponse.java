package vn.teasmart.backend.dto.response;

public record TeaRegionResponse(
        Long regionId,
        String name,
        String description,
        String location,
        String imageUrl) {
}
