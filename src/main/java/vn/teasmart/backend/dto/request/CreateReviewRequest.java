package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateReviewRequest(
        @JsonDeserialize(using = UpdateReviewRequest.StrictLongDeserializer.class) @NotNull @Positive Long productId,
        @JsonDeserialize(using = UpdateReviewRequest.StrictIntegerDeserializer.class) @NotNull @Min(1) @Max(5) Integer rating,
        @JsonDeserialize(using = UpdateReviewRequest.StrictStringDeserializer.class) @Size(max = 2000) String comment) {
    public CreateReviewRequest {
        comment = UpdateReviewRequest.normalizeComment(comment);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown review field.");
    }
}
