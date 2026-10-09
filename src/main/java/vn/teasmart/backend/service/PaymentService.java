package vn.teasmart.backend.service;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.PaymentResponse;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.*;
import vn.teasmart.backend.exception.*;
import vn.teasmart.backend.repository.*;

@Service
@Transactional(readOnly = true)
public class PaymentService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final OrderStockService stock;
    private final EntityManager em;
    private final Set<String> finalFailurePairs;
    public PaymentService(OrderRepository orders, PaymentRepository payments, UserRepository users,
            OrderStockService stock, EntityManager em,
            @Value("${teasmart.vnpay.verified-final-failure-pairs:}") String pairs) {
        this.orders = orders; this.payments = payments; this.users = users; this.stock = stock; this.em = em;
        finalFailurePairs = Arrays.stream(pairs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        // Opt-in only after merchant confirms finality; never classify unknown/suspicious codes as final.
        if (finalFailurePairs.stream().anyMatch(s -> !s.matches("(09|10|11|12|13|24|51|65|75|79):02")))
            throw new IllegalArgumentException("Invalid final failure policy.");
    }
    public PaymentResponse customerStatus(Long userId, Long orderId) {
        orders.findByOrderIdAndUser_UserId(orderId, userId).orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        return PaymentResponse.from(requirePayment(orderId));
    }
    public PaymentResponse adminStatus(Long orderId) { return PaymentResponse.from(requirePayment(orderId)); }
    public PaymentResponse customerStatusByReference(Long userId, String reference) {
        Payment p = payments.findByMerchantReference(reference).orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        if (!p.getOrder().getUser().getUserId().equals(userId)) throw new ResourceNotFoundException("Payment not found.");
        return PaymentResponse.from(p);
    }
    private Payment requirePayment(Long orderId) {
        return payments.findByOrder_OrderId(orderId).orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
    }

    @Transactional
    public PaymentResponse confirmCod(Long adminId, Long orderId) {
        User admin = users.findById(adminId).orElseThrow(() -> PaymentException.conflict("ADMIN_NOT_AVAILABLE"));
        if (!"ADMIN".equals(admin.getRole()) || !"ACTIVE".equals(admin.getStatus())) throw PaymentException.conflict("ADMIN_NOT_AVAILABLE");
        Order order = orders.findLockedByOrderId(orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        Payment p = payments.findLockedByOrder_OrderId(orderId).orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        if (!"DELIVERED".equals(order.getOrderStatus()) || p.getPaymentMethod() != PaymentMethod.COD
                || p.getAmount().compareTo(order.getTotalAmount()) != 0 || p.getGateway() != null
                || p.getTransactionCode() != null || p.isReconciliationRequired()) throw PaymentException.conflict("COD_NOT_CONFIRMABLE");
        if (p.getPaymentStatus() == PaymentStatus.PAID) {
            if (p.getPaidAt() == null || p.getConfirmedByAdmin() == null) throw PaymentException.conflict("PAYMENT_INCONSISTENT");
            return PaymentResponse.from(p);
        }
        if (p.getPaymentStatus() != PaymentStatus.PENDING || p.getPaidAt() != null || p.getConfirmedByAdmin() != null)
            throw PaymentException.conflict("PAYMENT_INCONSISTENT");
        p.setPaymentStatus(PaymentStatus.PAID); p.setPaidAt(PaymentTime.now());
        p.setUpdatedAt(p.getPaidAt()); p.setConfirmedByAdmin(admin); payments.flush();
        return PaymentResponse.from(p);
    }

    @Transactional
    public String accept(VnpayGateway.Notification n, boolean reconciliation) {
        Payment found = payments.findByMerchantReference(n.reference()).orElse(null);
        if (found == null) return "01";
        Long orderId = found.getOrder().getOrderId();
        em.detach(found); // Do not reuse a pre-lock REPEATABLE_READ entity snapshot.
        Order order = orders.findLockedByOrderId(orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        Payment p = payments.findLockedByOrder_OrderId(orderId).orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        if (p.getPaymentMethod() != PaymentMethod.ONLINE || !"VNPAY".equals(p.getGateway())
                || !n.reference().equals(p.getMerchantReference())) return "01";
        if (p.getAmount().compareTo(n.amount()) != 0 || p.getAmount().compareTo(order.getTotalAmount()) != 0) return "04";
        if (n.success() && (n.paidAt() == null || n.paidAt().isBefore(p.getCreatedAt()))) return "99";
        String transactionCode = "VNPAY:" + n.transactionNo();
        if (p.getPaymentStatus() == PaymentStatus.PAID) {
            if (n.success() && transactionCode.equals(p.getTransactionCode())) return "02";
            p.setReconciliationRequired(true); p.setUpdatedAt(PaymentTime.now());
            if (reconciliation) p.setLastReconciliationAt(PaymentTime.now());
            payments.flush(); return "00"; // Persist the exception; never downgrade PAID.
        }
        if (p.getPaymentStatus() == PaymentStatus.FAILED && !n.success()) {
            if (n.responseCode().equals(p.getGatewayResponseCode()) && n.transactionStatus().equals(p.getGatewayTransactionStatus())) return "02";
            p.setReconciliationRequired(true); p.setUpdatedAt(PaymentTime.now()); payments.flush(); return "00";
        }
        LocalDateTime now = PaymentTime.now();
        p.setGatewayResponseCode(n.responseCode()); p.setGatewayTransactionStatus(n.transactionStatus());
        if (reconciliation) p.setLastReconciliationAt(now);
        if (n.success()) {
            p.setTransactionCode(transactionCode); p.setPaidAt(n.paidAt()); p.setPaymentStatus(PaymentStatus.PAID);
            p.setReconciliationRequired(!"PENDING".equals(order.getOrderStatus()) || order.getStockReleasedAt() != null);
            // A late success records received money but does not revive a cancelled order or reserve stock again.
        } else if (finalFailurePairs.contains(n.responseCode() + ":" + n.transactionStatus())
                && "PENDING".equals(order.getOrderStatus()) && order.getStockReleasedAt() == null) {
            stock.release(order);
            order.setOrderStatus("CANCELLED"); order.setUpdatedAt(now);
            p.setPaymentStatus(PaymentStatus.FAILED); p.setReconciliationRequired(false);
        } else {
            p.setReconciliationRequired(true); // Includes abandoned, suspicious and unverified finality.
        }
        p.setUpdatedAt(now); payments.flush(); return "00";
    }

    public Snapshot snapshot(Long orderId) {
        Payment p = requirePayment(orderId);
        if (p.getPaymentMethod() != PaymentMethod.ONLINE || !"VNPAY".equals(p.getGateway())) throw PaymentException.conflict("NOT_VNPAY_PAYMENT");
        return new Snapshot(orderId, p.getMerchantReference(), p.getCreatedAt(), p.getPaymentStatus());
    }
    @Transactional
    public void unresolved(Long orderId) {
        orders.findLockedByOrderId(orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        Payment p = payments.findLockedByOrder_OrderId(orderId).orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        if (p.getPaymentStatus() == PaymentStatus.PENDING && "VNPAY".equals(p.getGateway())) {
            p.setReconciliationRequired(true); p.setLastReconciliationAt(PaymentTime.now()); p.setUpdatedAt(PaymentTime.now());
        }
    }
    public List<Long> due() {
        return payments.findReconciliationCandidates(PaymentTime.now(), PaymentTime.now().minusMinutes(6), PageRequest.of(0, 20));
    }
    public record Snapshot(Long orderId, String reference, LocalDateTime createdAt, PaymentStatus status) { }
}
