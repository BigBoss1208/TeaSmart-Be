package vn.teasmart.backend.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.response.StoreResponse;
import vn.teasmart.backend.service.StoreService;

@RestController
@RequestMapping("/api/stores")
public class CustomerStoreController {

    private final StoreService service;

    public CustomerStoreController(StoreService service) {
        this.service = service;
    }

    @GetMapping
    public List<StoreResponse> listStores() {
        return service.listPublicStores();
    }

    @GetMapping("/{storeId}")
    public StoreResponse getStore(@PathVariable Long storeId) {
        return service.getPublicStore(storeId);
    }
}
