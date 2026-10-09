package vn.teasmart.backend.repository;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrder_OrderId(Long orderId);

    // Current-read purchase proof after locking User; no Product lock is acquired.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrderItem> findFirstByOrder_User_UserIdAndProduct_ProductIdAndOrder_OrderStatusOrderByOrderItemIdAsc(
            Long userId, Long productId, String orderStatus);
}
