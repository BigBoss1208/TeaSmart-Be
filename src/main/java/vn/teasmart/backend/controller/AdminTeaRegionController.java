package vn.teasmart.backend.controller;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.AdminTeaRegionRequest;
import vn.teasmart.backend.dto.request.AdminTeaRegionStatusRequest;
import vn.teasmart.backend.dto.response.AdminTeaRegionResponse;
import vn.teasmart.backend.service.AdminTeaRegionService;

@RestController
@RequestMapping("/api/admin/tea-regions")
public class AdminTeaRegionController {
    private final AdminTeaRegionService service;

    public AdminTeaRegionController(AdminTeaRegionService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminTeaRegionResponse> getAll() {
        return service.getAll();
    }

    @GetMapping("/{regionId}")
    public AdminTeaRegionResponse getById(@PathVariable Long regionId) {
        return service.getById(regionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminTeaRegionResponse create(@Valid @RequestBody AdminTeaRegionRequest request) {
        return service.create(request);
    }

    @PutMapping("/{regionId}")
    public AdminTeaRegionResponse update(@PathVariable Long regionId,
            @Valid @RequestBody AdminTeaRegionRequest request) {
        return service.update(regionId, request);
    }

    @PatchMapping("/{regionId}/status")
    public AdminTeaRegionResponse updateStatus(@PathVariable Long regionId,
            @Valid @RequestBody AdminTeaRegionStatusRequest request) {
        return service.updateStatus(regionId, request);
    }
}
