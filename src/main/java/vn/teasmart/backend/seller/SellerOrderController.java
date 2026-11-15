package vn.teasmart.backend.seller;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/seller/orders")
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
public class SellerOrderController {
 private final SellerOrderService service;
 public SellerOrderController(SellerOrderService service){this.service=service;}
 @GetMapping public SellerOrderService.Page list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="12") @Min(1) @Max(50) int size){return service.list(Long.parseLong(jwt.getSubject()),q,status,page,size);}
 @GetMapping("/{id}") public SellerOrderService.Detail get(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive Long id){return service.get(Long.parseLong(jwt.getSubject()),id);}
 @PatchMapping("/{id}/status") public SellerOrderService.Detail status(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive Long id,@jakarta.validation.Valid @RequestBody vn.teasmart.backend.dto.request.AdminOrderStatusRequest request){return service.update(Long.parseLong(jwt.getSubject()),id,request.status());}
}
