package vn.teasmart.backend.repository;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import vn.teasmart.backend.entity.User;
public interface AdminCustomerRepository extends JpaRepository<User,Long> {
    @Query("select u from User u where u.role = 'CUSTOMER' and (:status is null or u.status = :status) and (lower(u.fullName) like :keyword escape '!' or lower(u.email) like :keyword escape '!' or lower(coalesce(u.phone,'')) like :keyword escape '!')")
    Page<User> searchCustomers(String status, String keyword, Pageable pageable);
    Optional<User> findByUserIdAndRole(Long userId, String role);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findLockedByUserIdAndRole(Long userId, String role);
}
