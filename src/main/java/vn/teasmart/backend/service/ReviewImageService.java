package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import vn.teasmart.backend.dto.response.ReviewImageResponse;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.ReviewStatus;
import vn.teasmart.backend.exception.*;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.storage.*;

@Service
public class ReviewImageService {
    private final UserRepository users;
    private final ReviewRepository reviews;
    private final ReviewImageRepository images;
    private final ReviewImageStorage storage;
    private final ReviewImageValidator validator;
    private final TransactionTemplate transactions;
    private record StagedImage(String contentType, long fileSize) {}
    public ReviewImageService(UserRepository users, ReviewRepository reviews, ReviewImageRepository images,
            ReviewImageStorage storage, ReviewImageValidator validator, PlatformTransactionManager manager) {
        this.users=users; this.reviews=reviews; this.images=images; this.storage=storage; this.validator=validator;
        this.transactions=new TransactionTemplate(manager);
    }
    public List<ReviewImageResponse> upload(Long userId, Long reviewId, List<MultipartFile> files) {
        // Decode and stage before opening a database transaction or acquiring locks.
        var normalized=validator.normalize(files);
        List<String> keys=new ArrayList<>();
        List<StagedImage> staged=new ArrayList<>();
        AtomicBoolean synchronizedCleanup=new AtomicBoolean(false);
        try {
            for (var file : normalized) {
                keys.add(storage.stage(file.bytes(),file.extension()));
                staged.add(new StagedImage(file.contentType(),file.bytes().length));
            }
            // Do not retain decoded upload bytes while waiting for database locks.
            normalized.clear();
            return transactions.execute(transaction -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status==STATUS_ROLLED_BACK) keys.forEach(storage::discard);
                        // STATUS_UNKNOWN: preserve files until metadata is reconciled by an operator.
                    }
                });
                synchronizedCleanup.set(true);
                Review review=ownedLockedReview(userId,reviewId);
                // Locking current-read, rather than snapshot COUNT under REPEATABLE_READ.
                int current=images.findLockedByReview_ReviewIdOrderByReviewImageIdAsc(reviewId).size();
                if (current+keys.size()>5) throw new ReviewImageException(HttpStatus.CONFLICT,
                        "REVIEW_IMAGE_LIMIT_EXCEEDED","A review may contain at most five images.");
                LocalDateTime now=LocalDateTime.now().withNano(0);
                List<ReviewImageResponse> result=new ArrayList<>();
                for (int i=0;i<keys.size();i++) {
                    var file=staged.get(i);
                    storage.finalizeFile(keys.get(i));
                    ReviewImage image=new ReviewImage();
                    image.setReview(review); image.setStorageKey(keys.get(i)); image.setContentType(file.contentType());
                    image.setFileSize(file.fileSize()); image.setCreatedAt(now);
                    images.saveAndFlush(image);
                    result.add(ReviewImageMapper.toResponse(image));
                }
                changed(review,now); reviews.flush();
                return List.copyOf(result);
            });
        } catch (RuntimeException e) {
            if (!synchronizedCleanup.get()) keys.forEach(storage::discard);
            throw e;
        }
    }
    @Transactional
    public void delete(Long userId,Long reviewId,Long imageId) {
        Review review=ownedLockedReview(userId,reviewId);
        ReviewImage image=images.findLockedByReviewImageIdAndReview_ReviewId(imageId,reviewId).orElseThrow(this::notFound);
        String key=image.getStorageKey();
        images.delete(image); images.flush();
        changed(review,LocalDateTime.now().withNano(0)); reviews.flush();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { storage.deleteAfterCommit(key); }
        });
    }
    public record BinaryImage(byte[] bytes,String contentType) {}
    @Transactional(readOnly=true)
    public BinaryImage get(Long imageId,Long userId,boolean admin) {
        ReviewImage image=images.findById(imageId).orElseThrow(this::notFound);
        Review review=image.getReview();
        boolean allowed=admin || review.getStatus()==ReviewStatus.APPROVED
                || (review.getStatus()!=ReviewStatus.DELETED && userId!=null && userId.equals(review.getUser().getUserId()));
        if (!allowed) throw notFound();
        return new BinaryImage(storage.read(image.getStorageKey()),image.getContentType());
    }
    private Review ownedLockedReview(Long userId,Long reviewId) {
        User user=users.findLockedByUserId(userId).orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"CUSTOMER".equals(user.getRole()) || !"ACTIVE".equals(user.getStatus())) throw new BadCredentialsException("Authentication failed.");
        Review review=reviews.findLockedByReviewIdAndUser_UserId(reviewId,userId).orElseThrow(this::notFound);
        if (review.getStatus()==ReviewStatus.DELETED) throw new ReviewConflictException("REVIEW_DELETED","Review has been deleted.");
        return review;
    }
    private void changed(Review review,LocalDateTime now) {
        if (review.getStatus()==ReviewStatus.APPROVED) review.setStatus(ReviewStatus.PENDING);
        review.setUpdatedAt(now);
    }
    private ResourceNotFoundException notFound() { return new ResourceNotFoundException("Review image or review not found."); }
}
