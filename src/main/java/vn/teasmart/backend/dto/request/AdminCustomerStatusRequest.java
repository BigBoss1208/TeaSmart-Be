package vn.teasmart.backend.dto.request;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record AdminCustomerStatusRequest(@NotBlank @Pattern(regexp="ACTIVE|INACTIVE") String status) {
    @JsonAnySetter public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported customer status field.");
    }
}
