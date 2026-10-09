package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.PlaceOrderRequest;
import vn.teasmart.backend.dto.response.OrderResponse;
import vn.teasmart.backend.dto.response.OrderSummaryResponse;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.service.OrderService;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public org.springframework.http.ResponseEntity<OrderResponse> placeOrder(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = service.checkout(Long.valueOf(jwt.getSubject()), request, key,
                vn.teasmart.backend.enums.PaymentMethod.COD, "127.0.0.1");
        return org.springframework.http.ResponseEntity.status(result.replayed() ? 200 : 201).body(result.order());
    }

    @PostMapping("/vnpay")
    public org.springframework.http.ResponseEntity<vn.teasmart.backend.dto.response.PaymentCheckoutResponse> checkoutVnpay(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            jakarta.servlet.http.HttpServletRequest http) {
        var result = service.checkout(Long.valueOf(jwt.getSubject()), request, key,
                vn.teasmart.backend.enums.PaymentMethod.ONLINE, http.getRemoteAddr());
        return org.springframework.http.ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
    }

    @GetMapping
    public PageResponse<OrderSummaryResponse> listOrders(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("Invalid pagination.");
        }
        return service.listOrders(Long.valueOf(jwt.getSubject()),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "orderId")));
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long orderId) {
        return service.getOrder(Long.valueOf(jwt.getSubject()), orderId);
    }

    @PatchMapping("/{orderId}/cancel")
    public OrderResponse cancelOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long orderId) {
        return service.cancelOrder(Long.valueOf(jwt.getSubject()), orderId);
    }
}
