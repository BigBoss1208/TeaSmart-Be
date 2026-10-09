package vn.teasmart.backend.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.teasmart.backend.entity.ReviewAiAnalysis;

public interface ReviewAiAnalysisRepository extends JpaRepository<ReviewAiAnalysis, Long> {
    Optional<ReviewAiAnalysis> findByReview_ReviewId(Long reviewId);

    // Call inside a transaction, after acquiring User -> Review locks.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReviewAiAnalysis> findLockedByReviewId(Long reviewId);
}
