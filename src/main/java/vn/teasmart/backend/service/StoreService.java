package vn.teasmart.backend.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.StoreResponse;
import vn.teasmart.backend.entity.Store;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.StoreRepository;

@Service
@Transactional(readOnly = true)
public class StoreService {

    private static final String ACTIVE = "ACTIVE";

    private final StoreRepository repository;

    public StoreService(StoreRepository repository) {
        this.repository = repository;
    }

    public List<StoreResponse> listPublicStores() {
        return repository.findByStatusOrderByNameAsc(ACTIVE).stream()
                .map(this::toResponse).toList();
    }

    public StoreResponse getPublicStore(Long storeId) {
        Store store = repository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found."));
        if (!ACTIVE.equals(store.getStatus())) {
            throw new ResourceNotFoundException("Store not found.");
        }
        return toResponse(store);
    }

    private StoreResponse toResponse(Store store) {
        return new StoreResponse(
                store.getStoreId(),
                store.getName(),
                store.getDescription(),
                store.getAddress(),
                store.getPhone(),
                store.getEmail(),
                store.getLogoUrl());
    }
}
