package vn.teasmart.backend.controller;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.AddressRequest;
import vn.teasmart.backend.dto.response.AddressResponse;
import vn.teasmart.backend.service.AddressService;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {
    private final AddressService service;

    public AddressController(AddressService service) {
        this.service = service;
    }

    @GetMapping
    public List<AddressResponse> getAll(@AuthenticationPrincipal Jwt jwt) {
        return service.getAll(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AddressRequest request) {
        return service.create(Long.valueOf(jwt.getSubject()), request);
    }

    @PutMapping("/{addressId}")
    public AddressResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long addressId,
            @Valid @RequestBody AddressRequest request) {
        return service.update(Long.valueOf(jwt.getSubject()), addressId, request);
    }

    @DeleteMapping("/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long addressId) {
        service.delete(Long.valueOf(jwt.getSubject()), addressId);
    }

    @PatchMapping("/{addressId}/default")
    public AddressResponse setDefault(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long addressId) {
        return service.setDefault(Long.valueOf(jwt.getSubject()), addressId);
    }
}
