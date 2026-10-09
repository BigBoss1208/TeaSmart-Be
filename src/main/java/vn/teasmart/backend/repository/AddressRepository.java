package vn.teasmart.backend.repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Address;

public interface AddressRepository extends JpaRepository<Address, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Address> findLockedByUser_UserIdOrderByAddressIdAsc(Long userId);

    List<Address> findByUser_UserId(Long userId);

    Optional<Address> findByAddressIdAndUser_UserId(Long addressId, Long userId);
}
