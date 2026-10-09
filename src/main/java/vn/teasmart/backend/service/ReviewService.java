package vn.teasmart.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.CreateReviewRequest;
import vn.teasmart.backend.dto.request.UpdateReviewRequest;
import vn.teasmart.backend.dto.response.*;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.ReviewStatus;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.exception.ReviewConflictException;
import vn.teasmart.backend.repository.*;

@Service
@Transactional(readOnly = true)
public class ReviewService {
    private final ReviewRepository reviews;
    private final UserRepository users;
    private final ProductRepository products;
    private final OrderItemRepository orderItems;
    private final ReviewImageMapper imageMapper;

    public ReviewService(ReviewRepository reviews, UserRepository users,
            ProductRepository products, OrderItemRepository orderItems, ReviewImageMapper imageMapper) {
        this.reviews = reviews;
        this.users = users;
        this.products = products;
        this.orderItems = orderItems;
        this.imageMapper = imageMapper;
    }

    @Transactional
    public ReviewResponse create(Long userId, CreateReviewRequest request) {
        User user = lockCustomer(userId);
        // Current-read includes DELETED; never read Review before acquiring User lock.
        if (reviews.findLockedByUser_UserIdAndProduct_ProductId(userId, request.productId()).isPresent()) {
            throw alreadyExists();
        }
        Product product = products.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
        // The locking query joins Order and selects the smallest delivered OrderItem ID.
        // Same-user Checkout/Cancel is serialized by User. No Product write lock is acquired.
        OrderItem proof = orderItems
                .findFirstByOrder_User_UserIdAndProduct_ProductIdAndOrder_OrderStatusOrderByOrderItemIdAsc(
                        userId, request.productId(), "DELIVERED")
                .orElseThrow(() -> new ReviewConflictException("REVIEW_NOT_ELIGIBLE",
                        "A delivered purchase of this product is required."));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Review review = new Review();
        review.setUser(user);
        review.setProduct(product);
        review.setOrderItem(proof);
        review.setRating(request.rating());
        review.setComment(request.comment());
        review.setStatus(ReviewStatus.PENDING);
        review.setCreatedAt(now);
        review.setUpdatedAt(now);
        try {
            reviews.saveAndFlush(review);
        } catch (DataIntegrityViolationException exception) {
            if (isReviewDuplicate(exception)) {
                throw alreadyExists();
            }
            throw exception;
        }
        return toResponse(review);
    }

    public PageResponse<ReviewResponse> getMine(Long userId, Pageable pageable) {
        Page<Review> page = reviews.findByUser_UserId(userId, pageable);
        var images = imageMapper.forReviews(page.getContent().stream().map(Review::getReviewId).toList());
        return new PageResponse<>(page.getContent().stream().map(review -> toResponse(review, images.getOrDefault(review.getReviewId(), java.util.List.of()))).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional
    public ReviewResponse update(Long userId, Long reviewId, UpdateReviewRequest request) {
        lockCustomer(userId);
        Review review = ownedLockedReview(userId, reviewId);
        requireNotDeleted(review);
        review.setRating(request.rating());
        review.setComment(request.comment());
        if (review.getStatus() == ReviewStatus.APPROVED) {
            review.setStatus(ReviewStatus.PENDING);
        }
        review.setUpdatedAt(LocalDateTime.now().withNano(0));
        reviews.flush();
        return toResponse(review);
    }

    @Transactional
    public void delete(Long userId, Long reviewId) {
        lockCustomer(userId);
        Review review = ownedLockedReview(userId, reviewId);
        requireNotDeleted(review);
        review.setStatus(ReviewStatus.DELETED);
        review.setUpdatedAt(LocalDateTime.now().withNano(0));
        reviews.flush();
    }

    public PageResponse<PublicReviewResponse> getPublic(Long productId, Pageable pageable) {
        requireProduct(productId);
        Page<Review> page = reviews.findByProduct_ProductIdAndStatus(productId, ReviewStatus.APPROVED, pageable);
        var images = imageMapper.forReviews(page.getContent().stream().map(Review::getReviewId).toList());
        return new PageResponse<>(page.getContent().stream().map(review -> new PublicReviewResponse(
                review.getReviewId(), review.getUser().getFullName(), review.getRating(), review.getComment(),
                review.getCreatedAt(), review.getUpdatedAt(), images.getOrDefault(review.getReviewId(), java.util.List.of()))).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public ReviewSummaryResponse summarize(Long productId) {
        requireProduct(productId);
        ReviewRepository.RatingSummary summary = reviews.summarize(productId, ReviewStatus.APPROVED);
        BigDecimal average = summary.getAverageRating() == null ? null
                : BigDecimal.valueOf(summary.getAverageRating()).setScale(2, RoundingMode.HALF_UP);
        return new ReviewSummaryResponse(productId, summary.getTotalReviews(), average);
    }

    private void requireProduct(Long productId) {
        // Review endpoints require product existence; catalog visibility is a separate contract.
        if (!products.existsById(productId)) {
            throw new ResourceNotFoundException("Product not found.");
        }
    }

    private User lockCustomer(Long userId) {
        User user = users.findLockedByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"CUSTOMER".equals(user.getRole()) || !"ACTIVE".equals(user.getStatus())) {
            throw new BadCredentialsException("Authentication failed.");
        }
        return user;
    }

    private Review ownedLockedReview(Long userId, Long reviewId) {
        return reviews.findLockedByReviewIdAndUser_UserId(reviewId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found."));
    }

    private void requireNotDeleted(Review review) {
        if (review.getStatus() == ReviewStatus.DELETED) {
            throw new ReviewConflictException("REVIEW_DELETED", "Review has been deleted.");
        }
    }

    private ReviewConflictException alreadyExists() {
        return new ReviewConflictException("REVIEW_ALREADY_EXISTS", "A review for this product already exists.");
    }

    private boolean isReviewDuplicate(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name != null) {
                    name = name.replace("`", "").replace("'", "");
                    if (name.equals("uk_reviews_user_id_product_id")
                            || name.endsWith(".uk_reviews_user_id_product_id")
                            || name.equals("uk_reviews_order_item_id")
                            || name.endsWith(".uk_reviews_order_item_id")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ReviewResponse toResponse(Review review) {
        return toResponse(review, imageMapper.forReview(review.getReviewId()));
    }

    private ReviewResponse toResponse(Review review, java.util.List<ReviewImageResponse> images) {
        return new ReviewResponse(review.getReviewId(), review.getProduct().getProductId(),
                review.getOrderItem().getOrderItemId(), review.getRating(), review.getComment(),
                review.getStatus(), review.getCreatedAt(), review.getUpdatedAt(), images);
    }
}
