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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "checkout_session_items", uniqueConstraints = {
    @UniqueConstraint(name = "uk_checkout_session_items_session_product", columnNames = {"checkout_session_id", "product_id"})
})
public class CheckoutSessionItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checkout_session_item_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long checkoutSessionItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checkout_session_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private CheckoutSession checkoutSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Product product;

    @Column(name = "source_cart_item_id", nullable = true, columnDefinition = "BIGINT UNSIGNED")
    private Long sourceCartItemId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @JdbcTypeCode(SqlTypes.BIGINT)
    @Column(name = "quantity", nullable = false, columnDefinition = "INT UNSIGNED")
    private Long quantity;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    public Long getCheckoutSessionItemId() { return checkoutSessionItemId; }
    public void setCheckoutSessionItemId(Long value) { checkoutSessionItemId = value; }
    public CheckoutSession getCheckoutSession() { return checkoutSession; }
    public void setCheckoutSession(CheckoutSession value) { checkoutSession = value; }
    public Product getProduct() { return product; }
    public void setProduct(Product value) { product = value; }
    public Long getSourceCartItemId() { return sourceCartItemId; }
    public void setSourceCartItemId(Long value) { sourceCartItemId = value; }
    public String getProductName() { return productName; }
    public void setProductName(String value) { productName = value; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { unitPrice = value; }
    public Long getQuantity() { return quantity; }
    public void setQuantity(Long value) { quantity = value; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal value) { subtotal = value; }
}
