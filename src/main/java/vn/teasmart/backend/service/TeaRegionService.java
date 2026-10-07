package vn.teasmart.backend.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.TeaRegionResponse;
import vn.teasmart.backend.entity.TeaRegion;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.TeaRegionRepository;

@Service
@Transactional(readOnly = true)
public class TeaRegionService {

    private static final String ACTIVE = "ACTIVE";

    private final TeaRegionRepository repository;

    public TeaRegionService(TeaRegionRepository repository) {
        this.repository = repository;
    }

    public List<TeaRegionResponse> listPublicTeaRegions() {
        return repository.findByStatusOrderByNameAsc(ACTIVE).stream()
                .map(this::toResponse).toList();
    }

    public TeaRegionResponse getPublicTeaRegion(Long regionId) {
        TeaRegion teaRegion = repository.findById(regionId)
                .orElseThrow(() -> new ResourceNotFoundException("TeaRegion not found."));
        if (!ACTIVE.equals(teaRegion.getStatus())) {
            throw new ResourceNotFoundException("TeaRegion not found.");
        }
        return toResponse(teaRegion);
    }

    private TeaRegionResponse toResponse(TeaRegion teaRegion) {
        return new TeaRegionResponse(
                teaRegion.getRegionId(),
                teaRegion.getName(),
                teaRegion.getDescription(),
                teaRegion.getLocation(),
                teaRegion.getImageUrl());
    }
}
