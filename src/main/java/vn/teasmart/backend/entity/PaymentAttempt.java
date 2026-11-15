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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.teasmart.backend.enums.PaymentAttemptStatus;

@Entity
@Table(name = "payment_attempts", uniqueConstraints = {
    @UniqueConstraint(name = "uk_payment_attempt_session_number", columnNames = {"checkout_session_id", "attempt_number"}),
    @UniqueConstraint(name = "uk_payment_attempt_session_key", columnNames = {"checkout_session_id", "idempotency_key"}),
    @UniqueConstraint(name = "uk_payment_attempt_merchant_reference", columnNames = {"merchant_reference"}),
    @UniqueConstraint(name = "uk_payment_attempt_gateway_transaction", columnNames = {"gateway", "transaction_no"}),
    @UniqueConstraint(name = "uk_payment_attempt_legacy_order", columnNames = {"legacy_order_id"})
})
public class PaymentAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_attempt_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long paymentAttemptId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checkout_session_id", columnDefinition = "BIGINT UNSIGNED")
    private CheckoutSession checkoutSession;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "legacy_order_id", columnDefinition = "BIGINT UNSIGNED")
    private Order legacyOrder;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "gateway", nullable = false, length = 20)
    private String gateway;

    @Column(name = "merchant_reference", nullable = false, length = 100,
            columnDefinition = "VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin")
    private String merchantReference;

    @Column(name = "idempotency_key", nullable = true, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentAttemptStatus status;

    @Column(name = "transaction_no", nullable = true, length = 30)
    private String transactionNo;

    @Column(name = "gateway_response_code", nullable = true, length = 2)
    private String gatewayResponseCode;

    @Column(name = "gateway_transaction_status", nullable = true, length = 2)
    private String gatewayTransactionStatus;

    @Column(name = "reconciliation_required", nullable = false, columnDefinition = "BOOLEAN")
    private boolean reconciliationRequired;

    @Column(name = "last_reconciliation_at", nullable = true, columnDefinition = "DATETIME")
    private LocalDateTime lastReconciliationAt;

    @Column(name = "paid_at", nullable = true, columnDefinition = "DATETIME")
    private LocalDateTime paidAt;

    @Column(name = "expires_at", nullable = true, columnDefinition = "DATETIME")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;

    public Long getPaymentAttemptId() { return paymentAttemptId; }
    public void setPaymentAttemptId(Long value) { paymentAttemptId = value; }
    public CheckoutSession getCheckoutSession() { return checkoutSession; }
    public void setCheckoutSession(CheckoutSession value) { checkoutSession = value; }
    public Order getLegacyOrder() { return legacyOrder; }
    public void setLegacyOrder(Order value) { legacyOrder = value; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer value) { attemptNumber = value; }
    public String getGateway() { return gateway; }
    public void setGateway(String value) { gateway = value; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String value) { merchantReference = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public PaymentAttemptStatus getStatus() { return status; }
    public void setStatus(PaymentAttemptStatus value) { status = value; }
    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String value) { transactionNo = value; }
    public String getGatewayResponseCode() { return gatewayResponseCode; }
    public void setGatewayResponseCode(String value) { gatewayResponseCode = value; }
    public String getGatewayTransactionStatus() { return gatewayTransactionStatus; }
    public void setGatewayTransactionStatus(String value) { gatewayTransactionStatus = value; }
    public boolean isReconciliationRequired() { return reconciliationRequired; }
    public void setReconciliationRequired(boolean value) { reconciliationRequired = value; }
    public LocalDateTime getLastReconciliationAt() { return lastReconciliationAt; }
    public void setLastReconciliationAt(LocalDateTime value) { lastReconciliationAt = value; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime value) { paidAt = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
