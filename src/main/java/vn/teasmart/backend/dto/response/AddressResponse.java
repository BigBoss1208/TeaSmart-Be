package vn.teasmart.backend.dto.response;

import java.time.LocalDateTime;

public record AddressResponse(Long addressId, String recipientName, String phone,
        String province, String district, String ward, String detailAddress,
        Boolean isDefault, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
