package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.AddCartItemRequest;
import vn.teasmart.backend.dto.request.UpdateCartItemRequest;
import vn.teasmart.backend.dto.response.CartResponse;
import vn.teasmart.backend.service.CartService;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public CartResponse getCart(@AuthenticationPrincipal Jwt jwt) {
        return service.getCart(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/items")
    public CartResponse addItem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddCartItemRequest request) {
        return service.addItem(Long.valueOf(jwt.getSubject()), request);
    }

    @PutMapping("/items/{cartItemId}")
    public CartResponse updateItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return service.updateItem(Long.valueOf(jwt.getSubject()), cartItemId, request);
    }

    @DeleteMapping("/items/{cartItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long cartItemId) {
        service.deleteItem(Long.valueOf(jwt.getSubject()), cartItemId);
    }
}
