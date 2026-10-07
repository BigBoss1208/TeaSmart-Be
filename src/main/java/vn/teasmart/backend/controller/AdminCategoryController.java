package vn.teasmart.backend.controller;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.AdminCategoryRequest;
import vn.teasmart.backend.dto.request.AdminCategoryStatusRequest;
import vn.teasmart.backend.dto.response.AdminCategoryResponse;
import vn.teasmart.backend.service.AdminCategoryService;

@RestController
@RequestMapping("/api/admin/categories")
public class AdminCategoryController {
    private final AdminCategoryService service;

    public AdminCategoryController(AdminCategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminCategoryResponse> getAll() {
        return service.getAll();
    }

    @GetMapping("/{categoryId}")
    public AdminCategoryResponse getById(@PathVariable Long categoryId) {
        return service.getById(categoryId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCategoryResponse create(@Valid @RequestBody AdminCategoryRequest request) {
        return service.create(request);
    }

    @PutMapping("/{categoryId}")
    public AdminCategoryResponse update(@PathVariable Long categoryId,
            @Valid @RequestBody AdminCategoryRequest request) {
        return service.update(categoryId, request);
    }

    @PatchMapping("/{categoryId}/status")
    public AdminCategoryResponse updateStatus(@PathVariable Long categoryId,
            @Valid @RequestBody AdminCategoryStatusRequest request) {
        return service.updateStatus(categoryId, request);
    }
}
