package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record AdminStoreRequest(
        @NotBlank @Size(max = 150) String name,
        String description,
        @Size(max = 255) String address,
        @Size(max = 20) String phone,
        @Email @Size(max = 255) String email,
        @Size(max = 500) String logoUrl,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    public AdminStoreRequest {
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
        address = address == null ? null : address.trim();
        phone = phone == null ? null : phone.trim();
        email = email == null ? null : email.trim();
        logoUrl = logoUrl == null ? null : logoUrl.trim();
    }

    @JsonIgnore
    @AssertTrue(message = "Description must not exceed 65535 UTF-8 bytes (MySQL TEXT).")
    public boolean isDescriptionWithinTextLimit() {
        return description == null || description.getBytes(StandardCharsets.UTF_8).length <= 65535;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown store field.");
    }
}
