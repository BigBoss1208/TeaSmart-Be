package vn.teasmart.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShippingInfoUpdateRequest(
        @NotBlank @Size(max = 100) String recipientName,
        @NotBlank @Pattern(regexp = "0[0-9]{9}") String recipientPhone,
        @NotBlank @Size(max = 500) String shippingAddress) {
    public ShippingInfoUpdateRequest {
        recipientName = recipientName == null ? null : recipientName.trim();
        recipientPhone = recipientPhone == null ? null : recipientPhone.trim();
        shippingAddress = shippingAddress == null ? null : shippingAddress.trim();
    }
}
