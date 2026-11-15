package vn.teasmart.backend.dto.request;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
public record GoogleLoginRequest(@NotBlank @Size(max=16384) String idToken) {
    @JsonAnySetter public void rejectUnknownField(String name,Object value) {
        throw new IllegalArgumentException("Unknown Google login field.");
    }
}
