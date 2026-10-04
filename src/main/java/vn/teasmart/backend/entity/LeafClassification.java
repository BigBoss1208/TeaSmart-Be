package vn.teasmart.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Persistence mapping for leaf_classifications in the frozen TeaSmart ERD.
 * Unsigned ID values are limited to the non-negative Java Long range.
 */
@Entity
@Table(name = "leaf_classifications")
public class LeafClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "classification_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long classificationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = true, columnDefinition = "BIGINT UNSIGNED")
    private User user;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "predicted_class", nullable = false, length = 100)
    private String predictedClass;

    @Column(name = "confidence", nullable = true, precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    public LeafClassification() {
    }

    public Long getClassificationId() {
        return classificationId;
    }

    public void setClassificationId(Long classificationId) {
        this.classificationId = classificationId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getPredictedClass() {
        return predictedClass;
    }

    public void setPredictedClass(String predictedClass) {
        this.predictedClass = predictedClass;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
