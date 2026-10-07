package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record AdminCategoryRequest(
        @NotBlank @Size(max = 100) String name,
        String description,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    public AdminCategoryRequest {
        name = name == null ? null : name.trim();
        description = description == null ? null : description.trim();
    }

    @JsonIgnore
    @AssertTrue(message = "Description must not exceed 65535 UTF-8 bytes (MySQL TEXT).")
    public boolean isDescriptionWithinTextLimit() {
        return description == null || description.getBytes(StandardCharsets.UTF_8).length <= 65535;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown category field.");
    }
}
