package vn.teasmart.backend.seller;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import vn.teasmart.backend.util.VietnamesePhone;
public final class SellerRequests {
    private SellerRequests() {}
    public record Profile(@NotBlank @Size(max=150) String shopName,
            @NotBlank @Size(max=100) String representativeName,
            @NotBlank @Pattern(regexp="^0[35789][0-9]{8}$") String phone,
            @NotBlank @Size(max=500) String businessAddress,
            @NotBlank @Size(max=3000) String description,
            @Size(max=40) String businessType,
            @Email @Size(max=254) String representativeEmail,
            @Size(max=100) String province,
            @Size(max=100) String ward,
            @Size(max=250) String addressDetail,
            @Size(max=500) String applicationLogoUrl,
            @Size(max=500) String coverUrl) {
        public Profile {
            shopName=trim(shopName); representativeName=trim(representativeName);
            phone=VietnamesePhone.normalize(phone); businessAddress=trim(businessAddress); description=trim(description);
        }
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException("Unsupported seller field."); }
    }
    public record Reject(@NotBlank @Size(max=1000) String reason) {
        public Reject { reason=trim(reason); }
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException("Unsupported decision field."); }
    }
    public record Status(@NotNull @Pattern(regexp="ACTIVE|SUSPENDED") String status,
            @Size(max=1000) String reason) {
        public Status { reason=trim(reason); }
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException("Unsupported status field."); }
    }
    private static String trim(String s) { return s==null?null:s.trim(); }
}
