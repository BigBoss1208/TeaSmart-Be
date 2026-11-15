package vn.teasmart.backend.controller;
import java.util.List;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.WishlistService;
import vn.teasmart.backend.dto.response.WishlistProductResponse;
@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {
    private final WishlistService service;
    public WishlistController(WishlistService service) { this.service = service; }
    @GetMapping public List<WishlistProductResponse> get(@AuthenticationPrincipal Jwt jwt) { return service.getAll(Long.valueOf(jwt.getSubject())); }
    @PutMapping("/products/{productId}") public List<WishlistProductResponse> add(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long productId) { return service.add(Long.valueOf(jwt.getSubject()), productId); }
    @DeleteMapping("/products/{productId}") public List<WishlistProductResponse> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long productId) { return service.remove(Long.valueOf(jwt.getSubject()), productId); }
}
