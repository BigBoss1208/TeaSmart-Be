package vn.teasmart.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "review_images", uniqueConstraints = @UniqueConstraint(name = "uk_review_images_storage_key", columnNames = "storage_key"), indexes = @Index(name = "idx_review_images_review_id_review_image_id",
        columnList = "review_id,review_image_id"))
public class ReviewImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_image_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long reviewImageId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false, columnDefinition = "BIGINT UNSIGNED", foreignKey = @ForeignKey(name = "fk_review_images_review_id"))
    private Review review;
    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;
    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;
    @Column(name = "file_size", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long fileSize;
    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;
    public Long getReviewImageId() { return reviewImageId; }
    public Review getReview() { return review; }
    public void setReview(Review review) { this.review = review; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
