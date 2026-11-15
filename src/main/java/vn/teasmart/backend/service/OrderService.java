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
import vn.teasmart.backend.dto.request.ShippingInfoUpdateRequest;
import vn.teasmart.backend.dto.response.*;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.enums.PaymentStatus;
import vn.teasmart.backend.exception.OrderConflictException;
import vn.teasmart.backend.exception.PaymentException;
import vn.teasmart.backend.config.VnpaySettings;
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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private vn.teasmart.backend.seller.CheckoutShopScope shopScope;
    private final OrderStockService stockService;
    private final VnpaySettings vnpaySettings;
    private final VnpayGateway gateway;
    private final vn.teasmart.backend.notification.OrderNotifications notifications;
    private final PaymentAttemptRepository paymentAttempts;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderService(UserRepository users, CartRepository carts, CartItemRepository cartItems,
            ProductRepository products, OrderRepository orders, OrderItemRepository orderItems,
            PaymentRepository payments, EntityManager entityManager, OrderStockService stockService, VnpaySettings vnpaySettings, VnpayGateway gateway, vn.teasmart.backend.notification.OrderNotifications notifications, PaymentAttemptRepository paymentAttempts) {
        this.users = users;
        this.carts = carts;
        this.cartItems = cartItems;
        this.products = products;
        this.orders = orders;
        this.orderItems = orderItems;
        this.payments = payments;
        this.entityManager = entityManager;
        this.stockService = stockService;
        this.vnpaySettings = vnpaySettings;
        this.gateway = gateway;
        this.notifications = notifications;
        this.paymentAttempts = paymentAttempts;
    }

    public OrderService(UserRepository users, CartRepository carts, CartItemRepository cartItems,
            ProductRepository products, OrderRepository orders, OrderItemRepository orderItems,
            PaymentRepository payments, EntityManager entityManager, OrderStockService stockService,
            VnpaySettings vnpaySettings, VnpayGateway gateway,
            vn.teasmart.backend.notification.OrderNotifications notifications) {
        this(users, carts, cartItems, products, orders, orderItems, payments, entityManager, stockService,
                vnpaySettings, gateway, notifications, null);
    }

    @Transactional
    public OrderResponse placeOrder(Long userId, PlaceOrderRequest request) {
        return checkout(userId, request, null, PaymentMethod.COD, "127.0.0.1").order();
    }

    @Transactional
    public PaymentCheckoutResponse checkout(Long userId, PlaceOrderRequest request, String rawKey,
            PaymentMethod method, String ip) {
        return checkoutInternal(userId, request, rawKey, method, ip, null, null);
    }

    @Transactional
    public PaymentCheckoutResponse buyNow(Long userId, vn.teasmart.backend.dto.request.BuyNowRequest request,
            String key, PaymentMethod method, String ip) {
        if (request.productId() == null || request.productId() < 1 || request.quantity() == null
                || request.quantity() < 1 || request.shipping() == null) throw PaymentException.invalid();
        CheckoutIdentity.key(key, true);
        return checkoutInternal(userId, request.shipping(), key, method, ip, request, null);
    }

    @Transactional
    public PaymentCheckoutResponse selected(Long userId, vn.teasmart.backend.dto.request.SelectedCheckoutRequest request,
            String key, PaymentMethod method, String ip) {
        CheckoutIdentity.key(key,true);
        CheckoutIdentity.selectedHash(request,method);
        return checkoutInternal(userId,request.shipping(),key,method,ip,null,request);
    }

    private PaymentCheckoutResponse checkoutInternal(Long userId, PlaceOrderRequest request, String rawKey,
            PaymentMethod method, String ip, vn.teasmart.backend.dto.request.BuyNowRequest direct, vn.teasmart.backend.dto.request.SelectedCheckoutRequest selected) {
        if (method != PaymentMethod.COD && method != PaymentMethod.ONLINE) throw PaymentException.invalid();
        String key = CheckoutIdentity.key(rawKey, method == PaymentMethod.ONLINE);
        String hash = selected != null ? CheckoutIdentity.selectedHash(selected,method) : direct == null ? CheckoutIdentity.hash(request, method)
                : CheckoutIdentity.buyNowHash(request, method, direct.productId(), direct.quantity());
        User user = users.findLockedByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus()) || !"CUSTOMER".equals(user.getRole())) {
            throw new BadCredentialsException("Authentication failed.");
        }
        if (key != null) {
            Order existing = orders.findLockedByUser_UserIdAndCheckoutKey(userId, key).orElse(null);
            if (existing != null) {
                if (!hash.equals(existing.getCheckoutRequestHash())) throw PaymentException.conflict("IDEMPOTENCY_KEY_REUSED");
                Payment payment = payments.findLockedByOrder_OrderId(existing.getOrderId())
                        .orElseThrow(() -> PaymentException.conflict("PAYMENT_MISSING"));
                return checkoutResponse(existing, orderItems.findByOrder_OrderId(existing.getOrderId()), payment, ip, true);
            }
        }
        // Legacy VNPay endpoints must not create official orders before payment verification.
        // Existing idempotent replays above remain readable for orders created before this flow.
        if (method == PaymentMethod.ONLINE) {
            throw PaymentException.conflict("CHECKOUT_SESSION_REQUIRED");
        }
        Cart cart = null;
        List<CartItem> items = List.of();
        Map<Long, Long> quantities = new TreeMap<>();
        if (direct == null) {
            cart = carts.findByUser_UserId(userId).orElseThrow(this::emptyCart);
            items = cartItems.findByCart_CartId(cart.getCartId());
            if(selected!=null) {
                Map<Long,CartItem> owned=new TreeMap<>();
                for(CartItem item:items) owned.put(item.getCartItemId(),item);
                List<CartItem> chosen=new ArrayList<>();
                for(var selection:selected.items().stream().sorted(Comparator.comparing(vn.teasmart.backend.dto.request.SelectedCheckoutRequest.Item::cartItemId)).toList()) {
                    CartItem item=owned.get(selection.cartItemId());
                    if(item==null || !selection.quantity().equals(item.getQuantity())) throw new OrderConflictException("CART_SELECTION_CHANGED","Selected cart items or quantities changed.");
                    chosen.add(item);
                }
                items=chosen;
            }
            if (items.isEmpty()) throw emptyCart();
            for (CartItem item : items) {
                quantities.put(item.getProduct().getProductId(), item.getQuantity());
                entityManager.detach(item.getProduct());
            }
        } else quantities.put(direct.productId(), direct.quantity());

        // CartItem access may initialize Product proxies before the locking query.
        var shop = shopScope==null ? null : shopScope.resolve(quantities.keySet());
        List<OrderItem> snapshots = new ArrayList<>();
        BigDecimal subtotal = ZERO;
        for (var selection : quantities.entrySet()) {
            Long productId = selection.getKey();
            Long quantity = selection.getValue();
            // Discard previously loaded state so the locking current read hydrates fresh values.
            // A subsequent plain refresh can read an older REPEATABLE_READ snapshot.
            Product product = products.findLockedByProductId(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
            requirePurchasable(product, quantity);
            BigDecimal price = requireAmount(product.getPrice());
            if (price.signum() <= 0) {
                throw new OrderConflictException("INVALID_PRODUCT_PRICE", "Product price must be positive.");
            }
            BigDecimal lineTotal = requireAmount(price.multiply(BigDecimal.valueOf(quantity)));
            subtotal = requireAmount(subtotal.add(lineTotal));
            OrderItem snapshot = new OrderItem();
            snapshot.setProduct(product);
            snapshot.setProductName(product.getName());
            snapshot.setUnitPrice(price);
            snapshot.setQuantity(quantity);
            snapshot.setSubtotal(lineTotal);
            snapshots.add(snapshot);
        }

        LocalDateTime now = PaymentTime.now();
        Order order = new Order();
        order.setOrderCode("TS-" + UUID.randomUUID().toString().replace("-", ""));
        order.setUser(user);
        order.setCheckoutKey(key);
        order.setCheckoutRequestHash(key == null ? null : hash);
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
        if(shopScope!=null)shopScope.saveOrder(order.getOrderId(),shop);

        for (OrderItem snapshot : snapshots) {
            snapshot.setOrder(order);
            orderItems.save(snapshot);
            Product product = snapshot.getProduct();
            product.setStockQuantity(product.getStockQuantity() - snapshot.getQuantity());
            product.setUpdatedAt(now);
        }
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(method);
        payment.setReconciliationRequired(false);
        if (method == PaymentMethod.ONLINE) {
            payment.setGateway("VNPAY");
            payment.setMerchantReference("TSVNP" + UUID.randomUUID().toString().replace("-", ""));
            payment.setExpiresAt(now.plusMinutes(vnpaySettings.expiryMinutes()));
        }
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(order.getTotalAmount());
        payment.setTransactionCode(null);
        payment.setPaidAt(null);
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);
        payments.save(payment);
        if (cart != null) {
            cartItems.deleteAll(items);
            cart.setUpdatedAt(now);
        }
        entityManager.flush();
        var response = checkoutResponse(order, snapshots, payment, ip, false);
        notifications.placed(order, snapshots, payment);
        return response;
    }

    private PaymentCheckoutResponse checkoutResponse(Order order, List<OrderItem> items, Payment payment, String ip, boolean replayed) {
        String url = null;
        if (payment.getPaymentMethod() == PaymentMethod.ONLINE && payment.getPaymentStatus() == PaymentStatus.PENDING
                && "PENDING".equals(order.getOrderStatus()) && !payment.isReconciliationRequired()
                && payment.getExpiresAt() != null && payment.getExpiresAt().isAfter(PaymentTime.now())) {
            try { url = gateway.paymentUrl(payment, ip); }
            catch (PaymentException e) {
                if (!replayed || !"VNPAY_UNAVAILABLE".equals(e.getCode())) throw e;
                // A replay remains a successful lookup even when the gateway configuration is unavailable.
            }
        }
        return new PaymentCheckoutResponse(toResponse(order, items, payment), url, payment.getExpiresAt(), replayed);
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
    public OrderResponse updateShippingInfo(Long userId, Long orderId, ShippingInfoUpdateRequest request) {
        User user = users.findLockedByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus()) || !"CUSTOMER".equals(user.getRole()))
            throw new BadCredentialsException("Authentication failed.");
        Order order = orders.findLockedByOrderIdAndUser_UserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        Payment payment = payments.findLockedByOrder_OrderId(orderId).orElse(null);
        if (!"PENDING".equals(order.getOrderStatus()) || payment == null || payment.isReconciliationRequired())
            throw new OrderConflictException("SHIPPING_INFO_LOCKED", "Shipping details can no longer be changed.");
        CheckoutSession session = order.getCheckoutSession();
        if (session != null && (session.getStatus() == vn.teasmart.backend.enums.CheckoutSessionStatus.RECONCILIATION_REQUIRED
                || session.getStatus() == vn.teasmart.backend.enums.CheckoutSessionStatus.PAID_PENDING_ORDER
                || paymentAttempts.existsByCheckoutSession_CheckoutSessionIdAndReconciliationRequiredTrue(session.getCheckoutSessionId())))
            throw new OrderConflictException("SHIPPING_INFO_RECONCILIATION", "This order is under payment reconciliation.");
        order.setRecipientName(request.recipientName());
        order.setRecipientPhone(request.recipientPhone());
        order.setShippingAddress(request.shippingAddress());
        order.setUpdatedAt(PaymentTime.now());
        entityManager.flush();
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
        Payment payment = payments.findLockedByOrder_OrderId(orderId).orElse(null);
        if (payment == null || payment.getPaymentMethod() != PaymentMethod.COD
                || payment.getPaymentStatus() != PaymentStatus.PENDING || payment.getPaidAt() != null
                || payment.getTransactionCode() != null) {
            throw new OrderConflictException("ORDER_PAYMENT_NOT_CANCELLABLE", "Order payment cannot be cancelled.");
        }
        stockService.release(order);
        List<OrderItem> items = orderItems.findByOrder_OrderId(orderId);
        LocalDateTime now = PaymentTime.now();
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
        boolean sellerListingActive = shopScope != null && shopScope.activeSellerListing(product.getProductId());
        if ((!"ACTIVE".equals(product.getStatus()) && !sellerListingActive) || !"ACTIVE".equals(product.getCategory().getStatus())
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
