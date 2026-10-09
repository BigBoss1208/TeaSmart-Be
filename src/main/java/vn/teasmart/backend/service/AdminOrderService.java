package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AdminOrderStatusRequest;
import vn.teasmart.backend.dto.response.AdminOrderResponse;
import vn.teasmart.backend.dto.response.AdminOrderSummaryResponse;
import vn.teasmart.backend.dto.response.OrderItemResponse;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.entity.Order;
import vn.teasmart.backend.entity.OrderItem;
import vn.teasmart.backend.entity.Payment;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.enums.PaymentStatus;
import vn.teasmart.backend.exception.OrderConflictException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.OrderItemRepository;
import vn.teasmart.backend.repository.OrderRepository;
import vn.teasmart.backend.repository.PaymentRepository;

@Service
@Transactional(readOnly = true)
public class AdminOrderService {
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "CONFIRMED", "SHIPPING", "DELIVERED", "CANCELLED");
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentRepository payments;

    public AdminOrderService(OrderRepository orders, OrderItemRepository orderItems,
            PaymentRepository payments) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.payments = payments;
    }

    public PageResponse<AdminOrderSummaryResponse> getAll(String orderStatus, Pageable pageable) {
        Page<Order> page = orderStatus == null ? orders.findAll(pageable)
                : orders.findByOrderStatus(orderStatus, pageable);
        return new PageResponse<>(page.getContent().stream().map(this::toSummary).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public AdminOrderResponse getById(Long orderId) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        return toResponse(order, payments.findByOrder_OrderId(orderId).orElse(null));
    }

    @Transactional
    public AdminOrderResponse updateStatus(Long orderId, AdminOrderStatusRequest request) {
        // Read current state under the Order lock; never acquire User or Product locks here.
        Order order = orders.findLockedByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        String current = order.getOrderStatus();
        if ("CANCELLED".equals(current)) {
            throw new OrderConflictException("ORDER_ALREADY_CANCELLED", "Order is already cancelled.");
        }
        if (current == null || !STATUSES.contains(current)) {
            throw invalidTransition();
        }
        if (current.equals(request.status())) {
            throw new OrderConflictException("ORDER_STATUS_UNCHANGED", "Order already has the requested status.");
        }
        Payment payment = payments.findByOrder_OrderId(orderId).orElse(null);
        if (payment == null || payment.getPaymentMethod() != PaymentMethod.COD
                || payment.getPaymentStatus() != PaymentStatus.PENDING || payment.getPaidAt() != null
                || payment.getTransactionCode() != null) {
            throw new OrderConflictException("ORDER_PAYMENT_NOT_PROCESSABLE", "Order payment cannot be processed.");
        }
        String next = switch (current) {
            case "PENDING" -> "CONFIRMED";
            case "CONFIRMED" -> "SHIPPING";
            case "SHIPPING" -> "DELIVERED";
            default -> null;
        };
        if (next == null || !next.equals(request.status())) {
            throw invalidTransition();
        }
        order.setOrderStatus(next);
        order.setUpdatedAt(LocalDateTime.now().withNano(0));
        orders.flush();
        return toResponse(order, payment);
    }

    private OrderConflictException invalidTransition() {
        return new OrderConflictException("INVALID_ORDER_TRANSITION", "Order status transition is not allowed.");
    }

    private AdminOrderSummaryResponse toSummary(Order order) {
        return new AdminOrderSummaryResponse(order.getOrderId(), order.getOrderCode(),
                order.getUser().getUserId(), order.getUser().getFullName(), order.getUser().getEmail(),
                order.getTotalAmount(), order.getOrderStatus(), order.getCreatedAt(), order.getUpdatedAt());
    }

    private AdminOrderResponse toResponse(Order order, Payment payment) {
        var items = orderItems.findByOrder_OrderId(order.getOrderId()).stream()
                .sorted(Comparator.comparing(OrderItem::getOrderItemId))
                .map(item -> new OrderItemResponse(item.getOrderItemId(), item.getProduct().getProductId(),
                        item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getSubtotal())).toList();
        return new AdminOrderResponse(order.getOrderId(), order.getOrderCode(), order.getUser().getUserId(),
                order.getUser().getFullName(), order.getUser().getEmail(), order.getRecipientName(),
                order.getRecipientPhone(), order.getShippingAddress(), order.getNote(), order.getSubtotal(),
                order.getShippingFee(), order.getTotalAmount(), order.getOrderStatus(),
                payment == null ? null : payment.getPaymentMethod(), payment == null ? null : payment.getPaymentStatus(),
                items, order.getCreatedAt(), order.getUpdatedAt());
    }
}
