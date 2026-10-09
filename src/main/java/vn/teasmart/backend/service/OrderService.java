package vn.teasmart.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.PlaceOrderRequest;
import vn.teasmart.backend.dto.response.*;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.enums.PaymentStatus;
import vn.teasmart.backend.exception.OrderConflictException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.*;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private final UserRepository users;
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentRepository payments;
    private final EntityManager entityManager;

    public OrderService(UserRepository users, CartRepository carts, CartItemRepository cartItems,
            ProductRepository products, OrderRepository orders, OrderItemRepository orderItems,
            PaymentRepository payments, EntityManager entityManager) {
        this.users = users;
        this.carts = carts;
        this.cartItems = cartItems;
        this.products = products;
        this.orders = orders;
        this.orderItems = orderItems;
        this.payments = payments;
        this.entityManager = entityManager;
    }

    @Transactional
    public OrderResponse placeOrder(Long userId, PlaceOrderRequest request) {
        User user = users.findLockedByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus()) || !"CUSTOMER".equals(user.getRole())) {
            throw new BadCredentialsException("Authentication failed.");
        }
        Cart cart = carts.findByUser_UserId(userId).orElseThrow(this::emptyCart);
        List<CartItem> items = cartItems.findByCart_CartId(cart.getCartId()).stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getProductId())).toList();
        if (items.isEmpty()) {
            throw emptyCart();
        }

        // CartItem access may initialize Product proxies before the locking query.
        List<OrderItem> snapshots = new ArrayList<>();
        BigDecimal subtotal = ZERO;
        for (CartItem item : items) {
            Long productId = item.getProduct().getProductId();
            // Discard previously loaded state so the locking current read hydrates fresh values.
            // A subsequent plain refresh can read an older REPEATABLE_READ snapshot.
            entityManager.detach(item.getProduct());
            Product product = products.findLockedByProductId(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
            requirePurchasable(product, item.getQuantity());
            BigDecimal price = requireAmount(product.getPrice());
            if (price.signum() <= 0) {
                throw new OrderConflictException("INVALID_PRODUCT_PRICE", "Product price must be positive.");
            }
            BigDecimal lineTotal = requireAmount(price.multiply(BigDecimal.valueOf(item.getQuantity())));
            subtotal = requireAmount(subtotal.add(lineTotal));
            OrderItem snapshot = new OrderItem();
            snapshot.setProduct(product);
            snapshot.setProductName(product.getName());
            snapshot.setUnitPrice(price);
            snapshot.setQuantity(item.getQuantity());
            snapshot.setSubtotal(lineTotal);
            snapshots.add(snapshot);
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);
        Order order = new Order();
        order.setOrderCode("TS-" + UUID.randomUUID().toString().replace("-", ""));
        order.setUser(user);
        order.setRecipientName(request.recipientName());
        order.setRecipientPhone(request.recipientPhone());
        order.setShippingAddress(request.shippingAddress());
        order.setNote(request.note());
        order.setSubtotal(subtotal);
        order.setShippingFee(ZERO);
        order.setTotalAmount(requireAmount(subtotal.add(ZERO)));
        order.setOrderStatus("PENDING");
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        saveOrder(order);

        for (OrderItem snapshot : snapshots) {
            snapshot.setOrder(order);
            orderItems.save(snapshot);
            Product product = snapshot.getProduct();
            product.setStockQuantity(product.getStockQuantity() - snapshot.getQuantity());
            product.setUpdatedAt(now);
        }
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(PaymentMethod.COD);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(order.getTotalAmount());
        payment.setTransactionCode(null);
        payment.setPaidAt(null);
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);
        payments.save(payment);
        cartItems.deleteAll(items);
        cart.setUpdatedAt(now);
        entityManager.flush();
        return toResponse(order, snapshots, payment);
    }

    public PageResponse<OrderSummaryResponse> listOrders(Long userId, Pageable pageable) {
        var page = orders.findByUser_UserId(userId, pageable);
        return new PageResponse<>(page.getContent().stream().map(order -> new OrderSummaryResponse(
                order.getOrderId(), order.getOrderCode(), order.getOrderStatus(), order.getTotalAmount(),
                order.getCreatedAt(), order.getUpdatedAt())).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public OrderResponse getOrder(Long userId, Long orderId) {
        Order order = orders.findByOrderIdAndUser_UserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        Payment payment = payments.findByOrder_OrderId(orderId).orElse(null);
        return toResponse(order, orderItems.findByOrder_OrderId(orderId), payment);
    }

    @Transactional
    public OrderResponse cancelOrder(Long userId, Long orderId) {
        User user = users.findLockedByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus()) || !"CUSTOMER".equals(user.getRole())) {
            throw new BadCredentialsException("Authentication failed.");
        }
        Order order = orders.findLockedByOrderIdAndUser_UserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        if ("CANCELLED".equals(order.getOrderStatus())) {
            throw new OrderConflictException("ORDER_ALREADY_CANCELLED", "Order is already cancelled.");
        }
        if (!"PENDING".equals(order.getOrderStatus())) {
            throw new OrderConflictException("ORDER_NOT_CANCELLABLE", "Only pending orders can be cancelled.");
        }
        Payment payment = payments.findByOrder_OrderId(orderId).orElse(null);
        if (payment == null || payment.getPaymentMethod() != PaymentMethod.COD
                || payment.getPaymentStatus() != PaymentStatus.PENDING || payment.getPaidAt() != null
                || payment.getTransactionCode() != null) {
            throw new OrderConflictException("ORDER_PAYMENT_NOT_CANCELLABLE", "Order payment cannot be cancelled.");
        }
        List<OrderItem> items = orderItems.findByOrder_OrderId(orderId);
        if (items.isEmpty()) {
            throw invalidOrderItems();
        }
        final long maxStock = 4294967295L;
        Map<Long, Long> quantities = new TreeMap<>();
        Map<Long, Product> loadedProducts = new TreeMap<>();
        try {
            for (OrderItem item : items) {
                Product product = item.getProduct();
                Long quantity = item.getQuantity();
                if (product == null || product.getProductId() == null || product.getProductId() <= 0
                        || quantity == null || quantity <= 0 || quantity > maxStock) {
                    throw invalidOrderItems();
                }
                Long productId = product.getProductId();
                long accumulated = quantities.getOrDefault(productId, 0L);
                if (quantity > maxStock - accumulated) {
                    throw new OrderConflictException("STOCK_OVERFLOW", "Restored stock exceeds INT UNSIGNED limits.");
                }
                quantities.put(productId, accumulated + quantity);
                loadedProducts.put(productId, product);
            }
        } catch (EntityNotFoundException exception) {
            throw invalidOrderItems();
        }
        // Discard all pre-lock Product state before any Product has pending changes.
        loadedProducts.values().forEach(entityManager::detach);
        Map<Long, Product> lockedProducts = new TreeMap<>();
        for (Map.Entry<Long, Long> entry : quantities.entrySet()) {
            Product product = products.findLockedByProductId(entry.getKey()).orElseThrow(this::invalidOrderItems);
            Long stock = product.getStockQuantity();
            if (stock == null || stock < 0 || stock > maxStock) {
                throw new OrderConflictException("INVALID_ORDER_ITEMS", "Current product stock is invalid.");
            }
            if (entry.getValue() > maxStock - stock) {
                throw new OrderConflictException("STOCK_OVERFLOW", "Restored stock exceeds INT UNSIGNED limits.");
            }
            lockedProducts.put(entry.getKey(), product);
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (Map.Entry<Long, Product> entry : lockedProducts.entrySet()) {
            Product product = entry.getValue();
            product.setStockQuantity(product.getStockQuantity() + quantities.get(entry.getKey()));
            product.setUpdatedAt(now);
        }
        order.setOrderStatus("CANCELLED");
        order.setUpdatedAt(now);
        entityManager.flush();
        return toResponse(order, items, payment);
    }

    private OrderConflictException invalidOrderItems() {
        return new OrderConflictException("INVALID_ORDER_ITEMS", "Order items contain missing or invalid product data.");
    }

    private void requirePurchasable(Product product, Long quantity) {
        if (quantity == null || quantity < 1 || quantity > 4294967295L) {
            throw new OrderConflictException("INVALID_QUANTITY", "Cart quantity is invalid.");
        }
        if (!"ACTIVE".equals(product.getStatus()) || !"ACTIVE".equals(product.getCategory().getStatus())
                || !"ACTIVE".equals(product.getRegion().getStatus()) || !"ACTIVE".equals(product.getStore().getStatus())) {
            throw new OrderConflictException("PRODUCT_UNAVAILABLE", "Product is unavailable.");
        }
        if (product.getStockQuantity() == 0) {
            throw new OrderConflictException("OUT_OF_STOCK", "Product is out of stock.");
        }
        if (quantity > product.getStockQuantity()) {
            throw new OrderConflictException("INSUFFICIENT_STOCK", "Requested quantity exceeds current stock.");
        }
    }

    private BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new OrderConflictException("AMOUNT_OUT_OF_RANGE", "Amount exceeds DECIMAL(12,2) limits.");
        }
        return amount;
    }

    private OrderConflictException emptyCart() {
        return new OrderConflictException("EMPTY_CART", "Cart is empty.");
    }

    private void saveOrder(Order order) {
        try {
            orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation && violation.getErrorCode() == 1062
                        && violation.getConstraintName() != null) {
                    String constraint = violation.getConstraintName().replace("`", "").replace("'", "");
                    if (constraint.equals("uk_orders_order_code") || constraint.equals("orders.uk_orders_order_code")) {
                        throw new OrderConflictException("ORDER_CODE_CONFLICT", "Order code already exists.");
                    }
                }
            }
            throw exception;
        }
    }

    private OrderResponse toResponse(Order order, List<OrderItem> items, Payment payment) {
        List<OrderItemResponse> responses = items.stream().sorted(Comparator.comparing(OrderItem::getOrderItemId))
                .map(item -> new OrderItemResponse(item.getOrderItemId(), item.getProduct().getProductId(),
                        item.getProductName(), item.getUnitPrice(), item.getQuantity(), item.getSubtotal())).toList();
        return new OrderResponse(order.getOrderId(), order.getOrderCode(), order.getRecipientName(),
                order.getRecipientPhone(), order.getShippingAddress(), order.getNote(), order.getSubtotal(),
                order.getShippingFee(), order.getTotalAmount(), order.getOrderStatus(),
                payment == null ? null : payment.getPaymentMethod(), payment == null ? null : payment.getPaymentStatus(),
                responses, order.getCreatedAt(), order.getUpdatedAt());
    }
}
