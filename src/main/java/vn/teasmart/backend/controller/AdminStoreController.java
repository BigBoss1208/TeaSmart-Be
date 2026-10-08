package vn.teasmart.backend.controller;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.AdminStoreRequest;
import vn.teasmart.backend.dto.request.AdminStoreStatusRequest;
import vn.teasmart.backend.dto.response.AdminStoreResponse;
import vn.teasmart.backend.service.AdminStoreService;

@RestController
@RequestMapping("/api/admin/stores")
public class AdminStoreController {
    private final AdminStoreService service;

    public AdminStoreController(AdminStoreService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminStoreResponse> getAll() {
        return service.getAll();
    }

    @GetMapping("/{storeId}")
    public AdminStoreResponse getById(@PathVariable Long storeId) {
        return service.getById(storeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminStoreResponse create(@Valid @RequestBody AdminStoreRequest request) {
        return service.create(request);
    }

    @PutMapping("/{storeId}")
    public AdminStoreResponse update(@PathVariable Long storeId,
            @Valid @RequestBody AdminStoreRequest request) {
        return service.update(storeId, request);
    }

    @PatchMapping("/{storeId}/status")
    public AdminStoreResponse updateStatus(@PathVariable Long storeId,
            @Valid @RequestBody AdminStoreStatusRequest request) {
        return service.updateStatus(storeId, request);
    }
}
