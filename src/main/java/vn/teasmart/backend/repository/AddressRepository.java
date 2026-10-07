package vn.teasmart.backend.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Address;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByUser_UserId(Long userId);

    Optional<Address> findByAddressIdAndUser_UserId(Long addressId, Long userId);
}
