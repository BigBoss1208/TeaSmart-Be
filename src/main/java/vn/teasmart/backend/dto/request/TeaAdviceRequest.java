package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record TeaAdviceRequest(@NotBlank @Size(max=1000) String message,
                               @Valid TeaPreferenceRequest preferences,
                               @Valid TeaPreferenceRequest context) {
    @JsonAnySetter public void rejectUnknownField(String field,Object value) {
        throw new IllegalArgumentException("Unsupported chat field.");
    }
}
