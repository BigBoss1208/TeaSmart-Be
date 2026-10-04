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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistence mapping for products in the frozen TeaSmart ERD.
 * Unsigned ID values are limited to the non-negative Java Long range.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", referencedColumnName = "category_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", referencedColumnName = "region_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private TeaRegion region;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", referencedColumnName = "store_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Store store;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 250)
    private String slug;

    @Column(name = "description", nullable = true, columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "stock_quantity", nullable = false, columnDefinition = "INT UNSIGNED")
    private Long stockQuantity;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "weight_grams", nullable = false, columnDefinition = "INT UNSIGNED")
    private Long weightGrams;

    @Column(name = "image_url", nullable = true, length = 500)
    private String imageUrl;

    @Column(name = "taste_note", nullable = true, length = 500)
    private String tasteNote;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "strength_level", nullable = true, columnDefinition = "TINYINT UNSIGNED")
    private Integer strengthLevel;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "astringency_level", nullable = true, columnDefinition = "TINYINT UNSIGNED")
    private Integer astringencyLevel;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "aroma_level", nullable = true, columnDefinition = "TINYINT UNSIGNED")
    private Integer aromaLevel;

    @JdbcTypeCode(SqlTypes.INTEGER)
    @Column(name = "aftertaste_level", nullable = true, columnDefinition = "TINYINT UNSIGNED")
    private Integer aftertasteLevel;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;

    public Product() {
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public TeaRegion getRegion() {
        return region;
    }

    public void setRegion(TeaRegion region) {
        this.region = region;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Long getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(Long stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public Long getWeightGrams() {
        return weightGrams;
    }

    public void setWeightGrams(Long weightGrams) {
        this.weightGrams = weightGrams;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getTasteNote() {
        return tasteNote;
    }

    public void setTasteNote(String tasteNote) {
        this.tasteNote = tasteNote;
    }

    public Integer getStrengthLevel() {
        return strengthLevel;
    }

    public void setStrengthLevel(Integer strengthLevel) {
        this.strengthLevel = strengthLevel;
    }

    public Integer getAstringencyLevel() {
        return astringencyLevel;
    }

    public void setAstringencyLevel(Integer astringencyLevel) {
        this.astringencyLevel = astringencyLevel;
    }

    public Integer getAromaLevel() {
        return aromaLevel;
    }

    public void setAromaLevel(Integer aromaLevel) {
        this.aromaLevel = aromaLevel;
    }

    public Integer getAftertasteLevel() {
        return aftertasteLevel;
    }

    public void setAftertasteLevel(Integer aftertasteLevel) {
        this.aftertasteLevel = aftertasteLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
