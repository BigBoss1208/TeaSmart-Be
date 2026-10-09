package vn.teasmart.backend.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findLockedByOrderId(Long orderId);

    Page<Order> findByOrderStatus(String orderStatus, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findLockedByOrderIdAndUser_UserId(Long orderId, Long userId);

    Page<Order> findByUser_UserId(Long userId, Pageable pageable);

    Optional<Order> findByOrderIdAndUser_UserId(Long orderId, Long userId);
}
