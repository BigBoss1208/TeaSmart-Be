package vn.teasmart.backend.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.response.TeaRegionResponse;
import vn.teasmart.backend.service.TeaRegionService;

@RestController
@RequestMapping("/api/tea-regions")
public class CustomerTeaRegionController {

    private final TeaRegionService service;

    public CustomerTeaRegionController(TeaRegionService service) {
        this.service = service;
    }

    @GetMapping
    public List<TeaRegionResponse> listTeaRegions() {
        return service.listPublicTeaRegions();
    }

    @GetMapping("/{regionId}")
    public TeaRegionResponse getTeaRegion(@PathVariable Long regionId) {
        return service.getPublicTeaRegion(regionId);
    }
}
