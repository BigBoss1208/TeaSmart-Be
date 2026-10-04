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
import java.time.LocalDateTime;

/**
 * Persistence mapping for product_views in the frozen TeaSmart ERD.
 * Unsigned ID values are limited to the non-negative Java Long range.
 */
@Entity
@Table(name = "product_views")
public class ProductView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "view_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long viewId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", referencedColumnName = "product_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Product product;

    @Column(name = "viewed_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime viewedAt;

    public ProductView() {
    }

    public Long getViewId() {
        return viewId;
    }

    public void setViewId(Long viewId) {
        this.viewId = viewId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public LocalDateTime getViewedAt() {
        return viewedAt;
    }

    public void setViewedAt(LocalDateTime viewedAt) {
        this.viewedAt = viewedAt;
    }
}
