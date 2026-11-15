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
import vn.teasmart.backend.entity.CheckoutSession;

public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, Long> {
    Optional<CheckoutSession> findByUser_UserIdAndIdempotencyKey(Long userId, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CheckoutSession> findLockedByUser_UserIdAndIdempotencyKey(Long userId, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CheckoutSession> findLockedByCheckoutSessionId(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CheckoutSession> findLockedByCheckoutSessionIdAndUser_UserId(Long id, Long userId);

    Optional<CheckoutSession> findByCheckoutSessionIdAndUser_UserId(Long id, Long userId);

    List<CheckoutSession> findTop20ByUser_UserIdAndStatusInOrderByCreatedAtDesc(Long userId, List<vn.teasmart.backend.enums.CheckoutSessionStatus> statuses);

    List<CheckoutSession> findTop100ByStatusInOrderByUpdatedAtAsc(List<vn.teasmart.backend.enums.CheckoutSessionStatus> statuses);

    @Query("select s.checkoutSessionId from CheckoutSession s where s.status in :statuses and s.reservationExpiresAt <= :now order by s.reservationExpiresAt")
    List<Long> findExpiredReservationCandidates(@Param("now") LocalDateTime now,
            @Param("statuses") List<vn.teasmart.backend.enums.CheckoutSessionStatus> statuses, Pageable pageable);
}
