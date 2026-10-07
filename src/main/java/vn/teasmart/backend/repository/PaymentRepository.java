package vn.teasmart.backend.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrder_OrderId(Long orderId);
}
