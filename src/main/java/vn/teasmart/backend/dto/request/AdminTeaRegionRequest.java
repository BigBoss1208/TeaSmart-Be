package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record AdminTeaRegionRequest(
        @NotBlank @Size(max = 150) String name,
        String description,
        @Size(max = 255) String location,
        @Size(max = 500) String imageUrl,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    public AdminTeaRegionRequest {
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
        location = location == null ? null : location.trim();
        imageUrl = imageUrl == null ? null : imageUrl.trim();
    }

    @JsonIgnore
    @AssertTrue(message = "Description must not exceed 65535 UTF-8 bytes (MySQL TEXT).")
    public boolean isDescriptionWithinTextLimit() {
        return description == null || description.getBytes(StandardCharsets.UTF_8).length <= 65535;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown tea region field.");
    }
}
