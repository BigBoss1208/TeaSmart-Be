package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.AdminProductRequest;
import vn.teasmart.backend.dto.request.AdminProductStatusRequest;
import vn.teasmart.backend.dto.response.AdminProductResponse;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.service.AdminProductService;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {
    private final AdminProductService service;

    public AdminProductController(AdminProductService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AdminProductResponse> listProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword) {
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("Invalid pagination.");
        }
        return service.listProducts(keyword, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "productId")));
    }

    @GetMapping("/{productId}")
    public AdminProductResponse getById(@PathVariable Long productId) {
        return service.getById(productId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProductResponse create(@Valid @RequestBody AdminProductRequest request) {
        return service.create(request);
    }

    @PutMapping("/{productId}")
    public AdminProductResponse update(@PathVariable Long productId, @Valid @RequestBody AdminProductRequest request) {
        return service.update(productId, request);
    }

    @PatchMapping("/{productId}/status")
    public AdminProductResponse updateStatus(@PathVariable Long productId,
            @Valid @RequestBody AdminProductStatusRequest request) {
        return service.updateStatus(productId, request);
    }
}
