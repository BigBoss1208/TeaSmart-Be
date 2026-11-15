package vn.teasmart.backend.seller;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/seller/products")
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
public class SellerProductController {
 private final SellerProductService service;
 public SellerProductController(SellerProductService service){this.service=service;}
 private Long user(Jwt jwt){return Long.valueOf(jwt.getSubject());}
 @GetMapping public Object list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size){return service.list(user(jwt),q,status,page,size);}
 @GetMapping("/options") public Object options(@AuthenticationPrincipal Jwt jwt){return service.options(user(jwt));}
 @GetMapping("/{id}") public Object get(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id){return service.get(user(jwt),id);}
 @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public Object create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody SellerProductRequests.Product request){return service.create(user(jwt),request);}
 @PatchMapping("/{id}") public Object update(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@Valid @RequestBody SellerProductRequests.Product request){return service.update(user(jwt),id,request);}
 @PatchMapping("/{id}/status") public Object status(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@Valid @RequestBody SellerProductRequests.Status request){return service.status(user(jwt),id,request.status());}
 @PostMapping("/{id}/archive") public Object archive(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id){return service.archive(user(jwt),id);}
 @PostMapping("/{id}/stock-adjustments") public Object adjust(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SellerProductRequests.Stock request){return service.adjust(user(jwt),id,key,request);}
}
