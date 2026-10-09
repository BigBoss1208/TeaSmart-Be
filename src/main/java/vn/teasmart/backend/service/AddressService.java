package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AddressRequest;
import vn.teasmart.backend.dto.response.AddressResponse;
import vn.teasmart.backend.entity.Address;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.AddressRepository;
import vn.teasmart.backend.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class AddressService {
    private final AddressRepository addresses;
    private final UserRepository users;

    public AddressService(AddressRepository addresses, UserRepository users) {
        this.addresses = addresses;
        this.users = users;
    }

    public List<AddressResponse> getAll(Long userId) {
        return addresses.findByUser_UserId(userId).stream()
                .sorted(Comparator.comparing(Address::getIsDefault).reversed()
                        .thenComparing(Address::getAddressId))
                .map(this::toResponse).toList();
    }

    @Transactional
    public AddressResponse create(Long userId, AddressRequest request) {
        User user = lockCustomer(userId);
        List<Address> owned = currentAddresses(userId);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Address address = new Address();
        address.setUser(user);
        apply(address, request);
        address.setIsDefault(owned.isEmpty());
        address.setCreatedAt(now);
        address.setUpdatedAt(now);
        addresses.saveAndFlush(address);
        return toResponse(address);
    }

    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressRequest request) {
        lockCustomer(userId);
        Address address = requireOwned(currentAddresses(userId), addressId);
        apply(address, request);
        address.setUpdatedAt(LocalDateTime.now().withNano(0));
        addresses.flush();
        return toResponse(address);
    }

    @Transactional
    public void delete(Long userId, Long addressId) {
        lockCustomer(userId);
        List<Address> owned = currentAddresses(userId);
        Address address = requireOwned(owned, addressId);
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            owned.stream().filter(candidate -> !candidate.getAddressId().equals(addressId))
                    .min(Comparator.comparing(Address::getAddressId)).ifPresent(replacement -> {
                        replacement.setIsDefault(true);
                        replacement.setUpdatedAt(LocalDateTime.now().withNano(0));
                    });
        }
        addresses.delete(address);
        addresses.flush();
    }

    @Transactional
    public AddressResponse setDefault(Long userId, Long addressId) {
        lockCustomer(userId);
        List<Address> owned = currentAddresses(userId);
        Address address = requireOwned(owned, addressId);
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            addresses.flush();
            return toResponse(address);
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (Address candidate : owned) {
            boolean selected = candidate.getAddressId().equals(addressId);
            if (!Boolean.valueOf(selected).equals(candidate.getIsDefault())) {
                candidate.setIsDefault(selected);
                candidate.setUpdatedAt(now);
            }
        }
        addresses.flush();
        return toResponse(address);
    }

    private User lockCustomer(Long userId) {
        User user = users.findLockedByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus()) || !"CUSTOMER".equals(user.getRole())) {
            throw new BadCredentialsException("Authentication failed.");
        }
        return user;
    }

    private List<Address> currentAddresses(Long userId) {
        // Lock User first, then current-read Address rows in ID order under REPEATABLE_READ.
        // No Address is loaded in this write transaction before acquiring the User lock.
        return addresses.findLockedByUser_UserIdOrderByAddressIdAsc(userId);
    }

    private Address requireOwned(List<Address> owned, Long addressId) {
        return owned.stream().filter(address -> address.getAddressId().equals(addressId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Address not found."));
    }

    private void apply(Address address, AddressRequest request) {
        address.setRecipientName(request.recipientName());
        address.setPhone(request.phone());
        address.setProvince(request.province());
        address.setDistrict(request.district());
        address.setWard(request.ward());
        address.setDetailAddress(request.detailAddress());
    }

    private AddressResponse toResponse(Address address) {
        return new AddressResponse(address.getAddressId(), address.getRecipientName(), address.getPhone(),
                address.getProvince(), address.getDistrict(), address.getWard(), address.getDetailAddress(),
                address.getIsDefault(), address.getCreatedAt(), address.getUpdatedAt());
    }
}
