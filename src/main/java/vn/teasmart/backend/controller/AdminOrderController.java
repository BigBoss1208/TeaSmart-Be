package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.AdminOrderStatusRequest;
import vn.teasmart.backend.dto.response.AdminOrderResponse;
import vn.teasmart.backend.dto.response.AdminOrderSummaryResponse;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.service.AdminOrderService;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {
    private final AdminOrderService service;

    public AdminOrderController(AdminOrderService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AdminOrderSummaryResponse> getAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size,
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING|CONFIRMED|SHIPPING|DELIVERED|CANCELLED") String orderStatus) {
        return service.getAll(orderStatus, PageRequest.of(page, size, Sort.by("orderId").descending()));
    }

    @GetMapping("/{orderId}")
    public AdminOrderResponse getById(@PathVariable @Positive Long orderId) {
        return service.getById(orderId);
    }

    @PatchMapping("/{orderId}/status")
    public AdminOrderResponse updateStatus(@PathVariable @Positive Long orderId,
            @Valid @RequestBody AdminOrderStatusRequest request) {
        return service.updateStatus(orderId, request);
    }
}
