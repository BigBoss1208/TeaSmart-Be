package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AdminStoreRequest;
import vn.teasmart.backend.dto.request.AdminStoreStatusRequest;
import vn.teasmart.backend.dto.response.AdminStoreResponse;
import vn.teasmart.backend.entity.Store;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.StoreRepository;


@Service
@Transactional(readOnly = true)
public class AdminStoreService {
    private final StoreRepository repository;

    public AdminStoreService(StoreRepository repository) {
        this.repository = repository;
    }

    public List<AdminStoreResponse> getAll() {
        return repository.findAll(Sort.by("name").ascending().and(Sort.by("storeId").ascending()))
                .stream().map(this::toResponse).toList();
    }

    public AdminStoreResponse getById(Long storeId) {
        return toResponse(requireRecord(storeId));
    }

    @Transactional
    public AdminStoreResponse create(AdminStoreRequest request) {

        Store record = new Store();
        applyRequest(record, request);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return save(record);
    }

    @Transactional
    public AdminStoreResponse update(Long storeId, AdminStoreRequest request) {
        Store record = requireRecord(storeId);

        applyRequest(record, request);
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(record);
    }

    @Transactional
    public AdminStoreResponse updateStatus(Long storeId, AdminStoreStatusRequest request) {
        Store record = requireRecord(storeId);
        record.setStatus(request.status());
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(record);
    }

    private Store requireRecord(Long storeId) {
        return repository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found."));
    }

    private void applyRequest(Store record, AdminStoreRequest request) {
        record.setName(request.name());
        record.setDescription(request.description());
        record.setAddress(request.address());
        record.setPhone(request.phone());
        record.setEmail(request.email());
        record.setLogoUrl(request.logoUrl());
        record.setStatus(request.status());
    }

    private AdminStoreResponse save(Store record) {
        repository.saveAndFlush(record);
        return toResponse(record);
    }

    private AdminStoreResponse toResponse(Store record) {
        return new AdminStoreResponse(record.getStoreId(),
                record.getName(), record.getDescription(), record.getAddress(), record.getPhone(), record.getEmail(), record.getLogoUrl(),
                record.getStatus(), record.getCreatedAt(), record.getUpdatedAt());
    }
}
