package vn.teasmart.backend.dto.request;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import vn.teasmart.backend.util.LoginIdentifier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 255) String email,
        @NotBlank String password) {
    public LoginRequest {
        email = email == null ? null : email.trim();
    }
    @JsonIgnore
    @AssertTrue(message = "Email hoặc số điện thoại không hợp lệ.")
    public boolean isIdentifierValid() {
        return LoginIdentifier.isValid(email);
    }
}
