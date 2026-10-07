package vn.teasmart.backend.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUser_UserId(Long userId, Pageable pageable);

    Optional<Order> findByOrderIdAndUser_UserId(Long orderId, Long userId);
}
