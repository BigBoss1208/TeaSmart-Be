package vn.teasmart.backend.seller;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name="teasmart.seller.enabled",havingValue="true")
public class AdminSellerController {
    private final SellerService service;
    public AdminSellerController(SellerService service) { this.service=service; }
    @GetMapping("/seller-capabilities") public java.util.Map<String,Boolean> capabilities() { return java.util.Map.of("profileDetailsEnabled",service.profileDetailsEnabled(),"foundationEnabled",service.foundationEnabled()); }
    @GetMapping("/seller-applications") public SellerService.Page<SellerApplication> applications(@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String businessType,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to) { return service.applications(status,page,size,q,businessType,from,to); }
    @GetMapping("/seller-applications/{id}") public SellerApplication application(@PathVariable Long id) { return service.application(id); }
    @PostMapping("/seller-applications/{id}/approve") public Shop approve(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id) { return service.approve(Long.valueOf(jwt.getSubject()),id); }
    @PostMapping("/seller-applications/{id}/reject") public SellerApplication reject(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@Valid @RequestBody SellerRequests.Reject request) { return service.reject(Long.valueOf(jwt.getSubject()),id,request.reason()); }
    @GetMapping("/shops") public SellerService.Page<Shop> shops(@RequestParam(defaultValue="ACTIVE") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String businessType,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to) { return service.shops(status,page,size,q,businessType,from,to); }
    @GetMapping("/shops/{id}") public Shop shop(@PathVariable Long id) { return service.adminShop(id); }
    @PatchMapping("/shops/{id}/status") public Shop status(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@Valid @RequestBody SellerRequests.Status request) { return service.status(Long.valueOf(jwt.getSubject()),id,request); }
}
