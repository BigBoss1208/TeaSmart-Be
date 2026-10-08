package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AdminTeaRegionRequest;
import vn.teasmart.backend.dto.request.AdminTeaRegionStatusRequest;
import vn.teasmart.backend.dto.response.AdminTeaRegionResponse;
import vn.teasmart.backend.entity.TeaRegion;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.TeaRegionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import vn.teasmart.backend.exception.TeaRegionConflictException;

@Service
@Transactional(readOnly = true)
public class AdminTeaRegionService {
    private final TeaRegionRepository repository;

    public AdminTeaRegionService(TeaRegionRepository repository) {
        this.repository = repository;
    }

    public List<AdminTeaRegionResponse> getAll() {
        return repository.findAll(Sort.by("name").ascending().and(Sort.by("regionId").ascending()))
                .stream().map(this::toResponse).toList();
    }

    public AdminTeaRegionResponse getById(Long regionId) {
        return toResponse(requireRecord(regionId));
    }

    @Transactional
    public AdminTeaRegionResponse create(AdminTeaRegionRequest request) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new TeaRegionConflictException("Tea region name already exists.");
        }
        TeaRegion record = new TeaRegion();
        applyRequest(record, request);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return save(record);
    }

    @Transactional
    public AdminTeaRegionResponse update(Long regionId, AdminTeaRegionRequest request) {
        TeaRegion record = requireRecord(regionId);
        if (repository.existsByNameIgnoreCaseAndRegionIdNot(request.name(), regionId)) {
            throw new TeaRegionConflictException("Tea region name already exists.");
        }
        applyRequest(record, request);
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(record);
    }

    @Transactional
    public AdminTeaRegionResponse updateStatus(Long regionId, AdminTeaRegionStatusRequest request) {
        TeaRegion record = requireRecord(regionId);
        record.setStatus(request.status());
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(record);
    }

    private TeaRegion requireRecord(Long regionId) {
        return repository.findById(regionId)
                .orElseThrow(() -> new ResourceNotFoundException("TeaRegion not found."));
    }

    private void applyRequest(TeaRegion record, AdminTeaRegionRequest request) {
        record.setName(request.name());
        record.setDescription(request.description());
        record.setLocation(request.location());
        record.setImageUrl(request.imageUrl());
        record.setStatus(request.status());
    }

    private AdminTeaRegionResponse save(TeaRegion record) {
        try {
            repository.saveAndFlush(record);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getErrorCode() == 1062 && violation.getConstraintName() != null) {
                    String constraint = violation.getConstraintName().replace("`", "").replace("'", "");
                    if (constraint.equals("uk_tea_regions_name") || constraint.equals("tea_regions.uk_tea_regions_name")) {
                        throw new TeaRegionConflictException("Tea region name already exists.");
                    }
                }
            }
            throw exception;
        }
        return toResponse(record);
    }

    private AdminTeaRegionResponse toResponse(TeaRegion record) {
        return new AdminTeaRegionResponse(record.getRegionId(),
                record.getName(), record.getDescription(), record.getLocation(), record.getImageUrl(),
                record.getStatus(), record.getCreatedAt(), record.getUpdatedAt());
    }
}
