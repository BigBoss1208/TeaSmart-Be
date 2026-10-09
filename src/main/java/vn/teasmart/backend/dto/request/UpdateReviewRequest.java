package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateReviewRequest(
        @JsonDeserialize(using = StrictIntegerDeserializer.class) @NotNull @Min(1) @Max(5) Integer rating,
        @JsonDeserialize(using = StrictStringDeserializer.class) @Size(max = 2000) String comment) {
    public UpdateReviewRequest {
        comment = normalizeComment(comment);
    }

    public static String normalizeComment(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static class StrictIntegerDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "Expected a JSON integer.");
            }
            return parser.getIntValue();
        }
    }

    public static class StrictLongDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "Expected a JSON integer.");
            }
            return parser.getLongValue();
        }
    }

    public static class StrictStringDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "Expected a JSON string.");
            }
            return parser.getString();
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown review field.");
    }
}
