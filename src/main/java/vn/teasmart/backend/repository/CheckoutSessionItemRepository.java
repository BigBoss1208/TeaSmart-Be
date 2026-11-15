package vn.teasmart.backend.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.CheckoutSessionItem;

public interface CheckoutSessionItemRepository extends JpaRepository<CheckoutSessionItem, Long> {
    List<CheckoutSessionItem> findByCheckoutSession_CheckoutSessionIdOrderByProduct_ProductId(Long sessionId);
}
