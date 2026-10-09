package vn.teasmart.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.teasmart.backend.entity.ReviewImage;

public interface ReviewImageRepository extends JpaRepository<ReviewImage, Long> {
    List<ReviewImage> findByReview_ReviewIdOrderByReviewImageIdAsc(Long reviewId);
    long countByReview_ReviewId(Long reviewId);
    List<ReviewImage> findByReview_ReviewIdInOrderByReviewImageIdAsc(Collection<Long> reviewIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ReviewImage> findLockedByReview_ReviewIdOrderByReviewImageIdAsc(Long reviewId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReviewImage> findLockedByReviewImageIdAndReview_ReviewId(Long imageId, Long reviewId);
}
