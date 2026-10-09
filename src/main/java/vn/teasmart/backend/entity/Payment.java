package vn.teasmart.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.enums.PaymentStatus;

/**
 * Persistence mapping for payments in the frozen TeaSmart ERD.
 * Unsigned ID values are limited to the non-negative Java Long range.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long paymentId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", referencedColumnName = "order_id", nullable = false, unique = true, columnDefinition = "BIGINT UNSIGNED")
    private Order order;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_method", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    private PaymentMethod paymentMethod;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_status", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    private PaymentStatus paymentStatus;

    @Column(name = "transaction_code", nullable = true, unique = true, length = 100)
    private String transactionCode;

    @Column(name = "paid_at", nullable = true, columnDefinition = "DATETIME")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;

    @Column(name = "gateway", nullable = true, columnDefinition = "VARCHAR(20)")
    private String gateway;

    public String getGateway() { return gateway; }
    public void setGateway(String value) { this.gateway = value; }

    @Column(name = "merchant_reference", nullable = true, columnDefinition = "VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin", unique = true)
    private String merchantReference;

    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String value) { this.merchantReference = value; }

    @Column(name = "expires_at", nullable = true, columnDefinition = "DATETIME")
    private LocalDateTime expiresAt;

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { this.expiresAt = value; }

    @Column(name = "gateway_response_code", nullable = true, columnDefinition = "VARCHAR(2)")
    private String gatewayResponseCode;

    public String getGatewayResponseCode() { return gatewayResponseCode; }
    public void setGatewayResponseCode(String value) { this.gatewayResponseCode = value; }

    @Column(name = "gateway_transaction_status", nullable = true, columnDefinition = "VARCHAR(2)")
    private String gatewayTransactionStatus;

    public String getGatewayTransactionStatus() { return gatewayTransactionStatus; }
    public void setGatewayTransactionStatus(String value) { this.gatewayTransactionStatus = value; }

    @Column(name = "reconciliation_required", nullable = false, columnDefinition = "BOOLEAN")
    private boolean reconciliationRequired;

    public boolean isReconciliationRequired() { return reconciliationRequired; }
    public void setReconciliationRequired(boolean value) { this.reconciliationRequired = value; }

    @Column(name = "last_reconciliation_at", nullable = true, columnDefinition = "DATETIME")
    private LocalDateTime lastReconciliationAt;

    public LocalDateTime getLastReconciliationAt() { return lastReconciliationAt; }
    public void setLastReconciliationAt(LocalDateTime value) { this.lastReconciliationAt = value; }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by_admin_id", columnDefinition = "BIGINT UNSIGNED")
    private User confirmedByAdmin;
    public User getConfirmedByAdmin() { return confirmedByAdmin; }
    public void setConfirmedByAdmin(User value) { this.confirmedByAdmin = value; }

    public Payment() {
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getTransactionCode() {
        return transactionCode;
    }

    public void setTransactionCode(String transactionCode) {
        this.transactionCode = transactionCode;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
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
