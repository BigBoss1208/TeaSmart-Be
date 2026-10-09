package vn.teasmart.backend.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findLockedByOrder_OrderId(Long orderId);

    Optional<Payment> findByMerchantReference(String reference);

    @Query("select p.order.orderId from Payment p where p.gateway = 'VNPAY' and p.paymentStatus = vn.teasmart.backend.enums.PaymentStatus.PENDING and p.expiresAt <= :now and (p.lastReconciliationAt is null or p.lastReconciliationAt < :retryBefore) order by p.expiresAt, p.paymentId")
    List<Long> findReconciliationCandidates(LocalDateTime now, LocalDateTime retryBefore, Pageable pageable);

    Optional<Payment> findByOrder_OrderId(Long orderId);
}
