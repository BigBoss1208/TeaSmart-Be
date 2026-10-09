package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.AdminReviewResponse;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.entity.Review;
import vn.teasmart.backend.enums.ReviewStatus;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.exception.ReviewConflictException;
import vn.teasmart.backend.repository.ReviewRepository;
import vn.teasmart.backend.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class AdminReviewService {
    private final ReviewRepository reviews;
    private final UserRepository users;

    public AdminReviewService(ReviewRepository reviews, UserRepository users) {
        this.reviews = reviews;
        this.users = users;
    }

    public PageResponse<AdminReviewResponse> getAll(Long productId, ReviewStatus status,
            Integer rating, Pageable pageable) {
        Page<Review> page = reviews.findForAdmin(productId, status, rating, pageable);
        return new PageResponse<>(page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public AdminReviewResponse getById(Long reviewId) {
        return toResponse(reviews.findById(reviewId).orElseThrow(this::notFound));
    }

    @Transactional
    public AdminReviewResponse approve(Long reviewId) {
        return moderate(reviewId, ReviewStatus.APPROVED);
    }

    @Transactional
    public AdminReviewResponse hide(Long reviewId) {
        return moderate(reviewId, ReviewStatus.HIDDEN);
    }

    private AdminReviewResponse moderate(Long reviewId, ReviewStatus target) {
        // Resolve only the immutable owner ID, without loading a managed Review.
        // Match Customer's User -> Review order, including before an idempotent PATCH.
        Long ownerId = reviews.findOwnerIdByReviewId(reviewId).orElseThrow(this::notFound);
        users.findLockedByUserId(ownerId).orElseThrow(this::notFound);
        Review review = reviews.findLockedByReviewId(reviewId).orElseThrow(this::notFound);
        // Check current state after locking: never revive a Customer-deleted Review.
        if (review.getStatus() == ReviewStatus.DELETED) {
            throw new ReviewConflictException("REVIEW_DELETED", "Review has been deleted.");
        }
        if (review.getStatus() != target) {
            review.setStatus(target);
            review.setUpdatedAt(LocalDateTime.now().withNano(0));
            reviews.flush();
        }
        return toResponse(review);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Review not found.");
    }

    private AdminReviewResponse toResponse(Review review) {
        return new AdminReviewResponse(review.getReviewId(), review.getProduct().getProductId(),
                review.getProduct().getName(), review.getUser().getUserId(), review.getUser().getFullName(),
                review.getOrderItem().getOrderItemId(), review.getRating(), review.getComment(), review.getStatus(),
                review.getCreatedAt(), review.getUpdatedAt());
    }
}
