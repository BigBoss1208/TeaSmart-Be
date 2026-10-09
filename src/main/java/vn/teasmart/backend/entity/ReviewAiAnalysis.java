package vn.teasmart.backend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.teasmart.backend.enums.AiAnalysisMethod;
import vn.teasmart.backend.enums.AiProcessingStatus;
import vn.teasmart.backend.enums.SentimentLabel;

/** Latest analysis metadata; the shared primary key is supplied by Review. */
@Entity
@Table(name = "review_ai_analysis")
public class ReviewAiAnalysis {
    @Id
    @Column(name = "review_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long reviewId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false, columnDefinition = "BIGINT UNSIGNED",
            foreignKey = @ForeignKey(name = "fk_review_ai_analysis_review_id"))
    private Review review;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sentiment_label", length = 20, columnDefinition = "VARCHAR(20)")
    private SentimentLabel sentimentLabel;

    @Column(name = "confidence", precision = 6, scale = 5)
    private BigDecimal confidence;

    @Column(name = "needs_review", columnDefinition = "BOOLEAN")
    private Boolean needsReview;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "moderation_flags", columnDefinition = "JSON")
    private String moderationFlags;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "analysis_method", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private AiAnalysisMethod analysisMethod;

    @Column(name = "model_version", length = 200)
    private String modelVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String inputHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "run_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String runId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "processing_status", nullable = false, length = 20, columnDefinition = "VARCHAR(20)")
    private AiProcessingStatus processingStatus;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "analyzed_at", columnDefinition = "DATETIME")
    private LocalDateTime analyzedAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;

    public ReviewAiAnalysis() {
    }

    public Long getReviewId() {
        return reviewId;
    }

    public Review getReview() {
        return review;
    }

    public void setReview(Review review) {
        this.review = review;
    }

    public SentimentLabel getSentimentLabel() {
        return sentimentLabel;
    }

    public void setSentimentLabel(SentimentLabel sentimentLabel) {
        this.sentimentLabel = sentimentLabel;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public Boolean getNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(Boolean needsReview) {
        this.needsReview = needsReview;
    }

    public String getModerationFlags() {
        return moderationFlags;
    }

    public void setModerationFlags(String moderationFlags) {
        this.moderationFlags = moderationFlags;
    }

    public AiAnalysisMethod getAnalysisMethod() {
        return analysisMethod;
    }

    public void setAnalysisMethod(AiAnalysisMethod analysisMethod) {
        this.analysisMethod = analysisMethod;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getInputHash() {
        return inputHash;
    }

    public void setInputHash(String inputHash) {
        this.inputHash = inputHash;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public AiProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(AiProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getAnalyzedAt() {
        return analyzedAt;
    }

    public void setAnalyzedAt(LocalDateTime analyzedAt) {
        this.analyzedAt = analyzedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
