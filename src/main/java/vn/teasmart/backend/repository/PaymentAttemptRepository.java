package vn.teasmart.backend.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.teasmart.backend.entity.PaymentAttempt;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByMerchantReference(String reference);
    Optional<PaymentAttempt> findByCheckoutSession_CheckoutSessionIdAndIdempotencyKey(Long sessionId, String key);
    Optional<PaymentAttempt> findFirstByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberDesc(Long sessionId);
    List<PaymentAttempt> findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(Long sessionId);
    boolean existsByCheckoutSession_CheckoutSessionIdAndReconciliationRequiredTrue(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentAttempt> findLockedByPaymentAttemptId(Long id);

    @Query("select a.paymentAttemptId from PaymentAttempt a where a.checkoutSession.checkoutSessionId = :sessionId and a.status in :statuses order by a.attemptNumber")
    List<Long> findUnresolvedAttemptIds(@Param("sessionId") Long sessionId,
            @Param("statuses") List<vn.teasmart.backend.enums.PaymentAttemptStatus> statuses);

    @Query("select a.paymentAttemptId from PaymentAttempt a where a.legacyOrder.orderId = :orderId")
    Optional<Long> findIdByLegacyOrderId(@Param("orderId") Long orderId);

    @Query("select a.paymentAttemptId from PaymentAttempt a where a.checkoutSession is not null and a.checkoutSession.status <> vn.teasmart.backend.enums.CheckoutSessionStatus.ADMIN_QUARANTINED and a.status in :statuses and a.expiresAt <= :now and (a.lastReconciliationAt is null or a.lastReconciliationAt < :retryBefore) order by a.expiresAt")
    List<Long> findReconciliationCandidates(@Param("now") LocalDateTime now,
            @Param("retryBefore") LocalDateTime retryBefore,
            @Param("statuses") List<vn.teasmart.backend.enums.PaymentAttemptStatus> statuses, Pageable pageable);
}
