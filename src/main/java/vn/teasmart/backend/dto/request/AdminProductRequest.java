package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

public record AdminProductRequest(
        @NotNull @Positive Long categoryId,
        @NotNull @Positive Long regionId,
        @NotNull @Positive Long storeId,
        @NotBlank @Size(max = 200) String name,
        String description,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Min(0) @Max(4294967295L) Long stockQuantity,
        @NotNull @Min(1) @Max(4294967295L) Long weightGrams,
        @Size(max = 500) String imageUrl,
        @Size(max = 500) String tasteNote,
        @Min(1) @Max(5) Integer strengthLevel,
        @Min(1) @Max(5) Integer astringencyLevel,
        @Min(1) @Max(5) Integer aromaLevel,
        @Min(1) @Max(5) Integer aftertasteLevel,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    public AdminProductRequest {
        name = name == null ? null : name.trim();
    }

    @JsonIgnore
    @AssertTrue(message = "Description must not exceed 65535 UTF-8 bytes (MySQL TEXT).")
    public boolean isDescriptionWithinTextLimit() {
        return description == null || description.getBytes(StandardCharsets.UTF_8).length <= 65535;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown product field.");
    }
}
