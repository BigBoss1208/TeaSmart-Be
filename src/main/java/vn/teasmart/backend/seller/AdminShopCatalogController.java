package vn.teasmart.backend.seller;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
public class AdminShopCatalogController {
    private final SellerFoundation foundation;
    public AdminShopCatalogController(SellerFoundation foundation) { this.foundation=foundation; }
    public record Link(@Positive Long storeId) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException("Unsupported field."); }
    }
    @GetMapping("/api/admin/shops/{id}/catalog-store")
    public SellerFoundation.CatalogLink get(@PathVariable Long id) { return foundation.catalog(id); }
    @PatchMapping("/api/admin/shops/{id}/catalog-store")
    public SellerFoundation.CatalogLink link(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@Valid @RequestBody Link request) {
        return foundation.link(Long.valueOf(jwt.getSubject()),id,request.storeId());
    }
}
