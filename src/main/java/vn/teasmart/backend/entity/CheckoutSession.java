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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.teasmart.backend.enums.CheckoutSessionStatus;
import vn.teasmart.backend.enums.ReservationStatus;

@Entity
@Table(name = "checkout_sessions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_checkout_sessions_user_key", columnNames = {"user_id", "idempotency_key"})
})
public class CheckoutSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checkout_session_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long checkoutSessionId;

    @Column(name = "session_code", nullable = false, unique = true, length = 50)
    private String sessionCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private User user;

    @Column(name = "idempotency_key", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64,
            columnDefinition = "CHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String requestHash;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    @Column(name = "note", nullable = true, length = 500)
    private String note;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "shipping_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CheckoutSessionStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reservation_status", nullable = false, length = 32)
    private ReservationStatus reservationStatus;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime expiresAt;

    @Column(name = "reservation_expires_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime reservationExpiresAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME")
    private LocalDateTime updatedAt;

    public Long getCheckoutSessionId() { return checkoutSessionId; }
    public void setCheckoutSessionId(Long value) { checkoutSessionId = value; }
    public String getSessionCode() { return sessionCode; }
    public void setSessionCode(String value) { sessionCode = value; }
    public User getUser() { return user; }
    public void setUser(User value) { user = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { sourceType = value; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String value) { recipientName = value; }
    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String value) { recipientPhone = value; }
    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String value) { shippingAddress = value; }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal value) { subtotal = value; }
    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal value) { shippingFee = value; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal value) { totalAmount = value; }
    public CheckoutSessionStatus getStatus() { return status; }
    public void setStatus(CheckoutSessionStatus value) { status = value; }
    public ReservationStatus getReservationStatus() { return reservationStatus; }
    public void setReservationStatus(ReservationStatus value) { reservationStatus = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    public LocalDateTime getReservationExpiresAt() { return reservationExpiresAt; }
    public void setReservationExpiresAt(LocalDateTime value) { reservationExpiresAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
