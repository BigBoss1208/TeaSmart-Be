package vn.teasmart.backend.seller;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="teasmart.seller.enabled",havingValue="true")
public class SellerController {
    private final SellerService service;
    public SellerController(SellerService service) { this.service=service; }
    @GetMapping("/api/seller-applications/me") public SellerService.State state(@AuthenticationPrincipal Jwt jwt) { return service.state(id(jwt)); }
    @PostMapping("/api/seller-applications") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public SellerApplication submit(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SellerRequests.Profile request) { return service.submit(id(jwt),request); }
    @GetMapping("/api/seller/shop") public Shop shop(@AuthenticationPrincipal Jwt jwt) { return service.myShop(id(jwt)); }
    @PatchMapping("/api/seller/shop") public Shop update(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SellerRequests.Profile request) { return service.update(id(jwt),request); }
    private static Long id(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }
}
