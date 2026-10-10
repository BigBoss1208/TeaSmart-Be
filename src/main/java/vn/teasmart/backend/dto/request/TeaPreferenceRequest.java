package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Explicit preferences override inferred text. Budget is VND per product pack, not per kilogram. */
public record TeaPreferenceRequest(
        @Positive Long categoryId,
        @Positive Long regionId,
        @DecimalMin("0.00") @DecimalMax("9999999999.99") @Digits(integer=10, fraction=2) BigDecimal minPrice,
        @DecimalMin("0.01") @DecimalMax("9999999999.99") @Digits(integer=10, fraction=2) BigDecimal maxPrice,
        @Min(1) @Max(5) Integer strength,
        @Min(1) @Max(5) Integer astringency,
        @Min(1) @Max(5) Integer aroma,
        @Min(1) @Max(5) Integer aftertaste,
        @Pattern(regexp="DAILY|GIFT") String purpose) {
    @JsonAnySetter public void rejectUnknownField(String field,Object value) {
        throw new IllegalArgumentException("Unsupported preference field.");
    }
    public static TeaPreferenceRequest empty() {
        return new TeaPreferenceRequest(null,null,null,null,null,null,null,null,null);
    }
}
