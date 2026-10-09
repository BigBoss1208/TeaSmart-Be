package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.config.ReviewAiSettings;
import vn.teasmart.backend.dto.response.ReviewAiAnalysisResponse;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.*;
import vn.teasmart.backend.exception.*;
import vn.teasmart.backend.repository.*;

@Service
public class ReviewAiAnalysisTransactionService {
    private final ReviewRepository reviews;
    private final UserRepository users;
    private final ReviewAiAnalysisRepository analyses;
    private final ReviewAiSettings settings;
    private final JsonMapper json = JsonMapper.builder().build();

    public ReviewAiAnalysisTransactionService(ReviewRepository reviews, UserRepository users,
            ReviewAiAnalysisRepository analyses, ReviewAiSettings settings) {
        this.reviews = reviews;
        this.users = users;
        this.analyses = analyses;
        this.settings = settings;
    }

    public record Run(Long reviewId, String runId, String inputHash, String comment, Integer rating) { }
    public record Completion(ReviewAiAnalysisResponse response, ReviewAiException error) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReviewAiAnalysisResponse get(Long adminId, Long reviewId) {
        requireAdmin(adminId);
        Review review = lockReview(reviewId);
        ReviewAiAnalysis analysis = analyses.findLockedByReviewId(reviewId).orElse(null);
        // On-demand lease recovery: no scheduler and no indefinitely visible PROCESSING.
        if (analysis != null && expired(analysis)) {
            fail(analysis, "REVIEW_AI_LEASE_EXPIRED");
        }
        return response(review, analysis);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Run begin(Long adminId, Long reviewId) {
        requireAdmin(adminId);
        Review review = lockReview(reviewId);
        if (review.getStatus() == ReviewStatus.DELETED) throw deleted();
        ReviewAiAnalysis analysis = analyses.findLockedByReviewId(reviewId).orElse(null);
        if (analysis != null && analysis.getProcessingStatus() == AiProcessingStatus.PROCESSING && !expired(analysis)) {
            throw conflict("REVIEW_AI_IN_PROGRESS", "Review analysis is already processing.");
        }
        if (analysis == null) {
            analysis = new ReviewAiAnalysis();
            analysis.setReview(review);
        }
        String hash = ReviewAiInputHasher.hash(review.getComment(), review.getRating());
        String runId = UUID.randomUUID().toString();
        analysis.setRunId(runId);
        analysis.setInputHash(hash);
        analysis.setAnalysisMethod(AiAnalysisMethod.MODEL);
        analysis.setProcessingStatus(AiProcessingStatus.PROCESSING);
        analysis.setSentimentLabel(null);
        analysis.setConfidence(null);
        analysis.setNeedsReview(null);
        analysis.setModerationFlags("[]");
        analysis.setModelVersion(null);
        analysis.setErrorCode(null);
        analysis.setAnalyzedAt(null);
        analysis.setUpdatedAt(now());
        analyses.saveAndFlush(analysis);
        return new Run(reviewId, runId, hash, review.getComment(), review.getRating());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Completion finish(Long adminId, Run run, ReviewAiClient.Result result, ReviewAiException failure) {
        requireAdmin(adminId);
        Review review = lockReview(run.reviewId());
        ReviewAiAnalysis analysis = analyses.findLockedByReviewId(run.reviewId()).orElse(null);
        if (analysis == null || !run.runId().equals(analysis.getRunId())
                || analysis.getProcessingStatus() != AiProcessingStatus.PROCESSING) {
            return error(conflict("REVIEW_AI_SUPERSEDED", "This analysis run is no longer current."));
        }
        // Return errors instead of throwing inside the transaction, so FAILED is committed.
        if (review.getStatus() == ReviewStatus.DELETED) {
            fail(analysis, "REVIEW_DELETED");
            return error(deleted());
        }
        String currentHash = ReviewAiInputHasher.hash(review.getComment(), review.getRating());
        if (!run.inputHash().equals(currentHash) || !run.inputHash().equals(analysis.getInputHash())) {
            fail(analysis, "REVIEW_AI_INPUT_CHANGED");
            return error(conflict("REVIEW_AI_INPUT_CHANGED", "Review content changed during analysis."));
        }
        if (expired(analysis)) {
            fail(analysis, "REVIEW_AI_LEASE_EXPIRED");
            return error(conflict("REVIEW_AI_LEASE_EXPIRED", "The analysis lease expired."));
        }
        if (failure != null) {
            fail(analysis, failure.getCode());
            return error(failure);
        }
        analysis.setSentimentLabel(result.sentiment());
        analysis.setConfidence(null);
        analysis.setNeedsReview(result.needsReview());
        analysis.setModerationFlags(json.writeValueAsString(result.flags()));
        analysis.setAnalysisMethod(result.method());
        analysis.setModelVersion(result.version());
        analysis.setProcessingStatus(result.status());
        analysis.setErrorCode(result.errorCode());
        analysis.setAnalyzedAt(now());
        analysis.setUpdatedAt(now());
        analyses.flush();
        return new Completion(response(review, analysis), null);
    }

    private void requireAdmin(Long adminId) {
        User user = users.findById(adminId).orElseThrow(() ->
                new ReviewAiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required."));
        if (!"ACTIVE".equals(user.getStatus()) || !"ADMIN".equals(user.getRole())) {
            throw new ReviewAiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Admin access is required.");
        }
    }

    private Review lockReview(Long reviewId) {
        // Resolve only immutable owner ID before current-read User -> Review -> Analysis.
        Long ownerId = reviews.findOwnerIdByReviewId(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found."));
        users.findLockedByUserId(ownerId).orElseThrow(() -> new ResourceNotFoundException("Review not found."));
        return reviews.findLockedByReviewId(reviewId).orElseThrow(() -> new ResourceNotFoundException("Review not found."));
    }

    private boolean expired(ReviewAiAnalysis analysis) {
        return analysis.getProcessingStatus() == AiProcessingStatus.PROCESSING
                && !analysis.getUpdatedAt().plusSeconds(settings.leaseSeconds()).isAfter(now());
    }

    private void fail(ReviewAiAnalysis analysis, String code) {
        analysis.setProcessingStatus(AiProcessingStatus.FAILED);
        analysis.setErrorCode(code);
        analysis.setUpdatedAt(now());
        analyses.flush();
    }

    private ReviewAiAnalysisResponse response(Review review, ReviewAiAnalysis analysis) {
        if (analysis == null) {
            return new ReviewAiAnalysisResponse(null, null, null, List.of(), List.of(),
                    null, null, "NOT_ANALYZED", null, false, null);
        }
        List<String> flags = new ArrayList<>();
        if (analysis.getModerationFlags() != null) {
            var node = json.readTree(analysis.getModerationFlags());
            if (!node.isArray() || node.size() > 5) throw invalidStoredResult();
            for (var item : node) {
                if (!item.isString() || !ReviewAiClient.FLAGS.contains(item.stringValue())) throw invalidStoredResult();
                flags.add(item.stringValue());
            }
        }
        boolean stale = !analysis.getInputHash().equals(ReviewAiInputHasher.hash(review.getComment(), review.getRating()));
        // Reasons are intentionally not persisted or reconstructed. GET and POST both return [].
        return new ReviewAiAnalysisResponse(analysis.getSentimentLabel(), analysis.getConfidence(),
                analysis.getNeedsReview(), List.copyOf(flags), List.of(), analysis.getAnalysisMethod(),
                analysis.getModelVersion(), analysis.getProcessingStatus().name(),
                analysis.getAnalyzedAt(), stale, analysis.getErrorCode());
    }

    private ReviewAiException invalidStoredResult() {
        return new ReviewAiException(HttpStatus.BAD_GATEWAY, "REVIEW_AI_INVALID_STORED_RESULT", "Stored analysis is invalid.");
    }
    private Completion error(ReviewAiException exception) { return new Completion(null, exception); }
    private ReviewAiException deleted() { return conflict("REVIEW_DELETED", "Review has been deleted."); }
    private ReviewAiException conflict(String code, String message) {
        return new ReviewAiException(HttpStatus.CONFLICT, code, message);
    }
    private LocalDateTime now() { return LocalDateTime.now().withNano(0); }
}
