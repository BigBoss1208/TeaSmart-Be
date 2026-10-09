package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record AddressRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Size(max = 100) String recipientName,
        @JsonDeserialize(using = StrictStringDeserializer.class)
        @NotBlank @Size(max = 20) @Pattern(regexp = "0[0-9]{9}") String phone,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Size(max = 100) String province,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Size(max = 100) String district,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Size(max = 100) String ward,
        @JsonDeserialize(using = StrictStringDeserializer.class) @NotBlank @Size(max = 255) String detailAddress) {
    public AddressRequest {
        recipientName = trim(recipientName);
        phone = trim(phone);
        province = trim(province);
        district = trim(district);
        ward = trim(ward);
        detailAddress = trim(detailAddress);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
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
        throw new IllegalArgumentException("Unknown address field.");
    }
}
