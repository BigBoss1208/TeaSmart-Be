package vn.teasmart.backend.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrder_OrderId(Long orderId);
}
