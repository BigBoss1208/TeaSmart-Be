package vn.teasmart.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Review;
import vn.teasmart.backend.enums.ReviewStatus;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByOrderItem_OrderItemId(Long orderItemId);

    Page<Review> findByProduct_ProductIdAndStatus(Long productId, ReviewStatus status, Pageable pageable);

    Page<Review> findByUser_UserId(Long userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Review> findLockedByUser_UserIdAndProduct_ProductId(Long userId, Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Review> findLockedByReviewIdAndUser_UserId(Long reviewId, Long userId);

    @Query("select r.user.userId from Review r where r.reviewId = :reviewId")
    Optional<Long> findOwnerIdByReviewId(@Param("reviewId") Long reviewId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Review> findLockedByReviewId(Long reviewId);

    @Query("select r from Review r where (:productId is null or r.product.productId = :productId) "
            + "and (:status is null or r.status = :status) and (:rating is null or r.rating = :rating)")
    Page<Review> findForAdmin(@Param("productId") Long productId, @Param("status") ReviewStatus status,
            @Param("rating") Integer rating, Pageable pageable);

    @Query("select count(r) as totalReviews, avg(r.rating) as averageRating from Review r "
            + "where r.product.productId = :productId and r.status = :status")
    RatingSummary summarize(@Param("productId") Long productId, @Param("status") ReviewStatus status);

    interface RatingSummary {
        Long getTotalReviews();
        Double getAverageRating();
    }
}
