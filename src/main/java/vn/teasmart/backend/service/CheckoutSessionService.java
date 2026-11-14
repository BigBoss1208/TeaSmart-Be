package vn.teasmart.backend.service;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.config.CheckoutSessionSettings;
import vn.teasmart.backend.config.VnpaySettings;
import vn.teasmart.backend.dto.request.CreateCheckoutSessionRequest;
import vn.teasmart.backend.dto.request.PlaceOrderRequest;
import vn.teasmart.backend.dto.response.CheckoutPaymentStatusResponse;
import vn.teasmart.backend.dto.response.CheckoutSessionActionResponse;
import vn.teasmart.backend.dto.response.CheckoutSessionResponse;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.*;
import vn.teasmart.backend.exception.OrderConflictException;
import vn.teasmart.backend.exception.PaymentException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.notification.OrderNotifications;

@Service
public class CheckoutSessionService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private vn.teasmart.backend.seller.CheckoutShopScope shopScope;
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private static final long MAX_STOCK = 4294967295L;
    private final UserRepository users;
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final ProductRepository products;
    private final CheckoutSessionRepository sessions;
    private final CheckoutSessionItemRepository sessionItems;
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PaymentRepository payments;
    private final EntityManager em;
    private final CheckoutSessionSettings timing;
    private final VnpaySettings vnpay;
    private final VnpayGateway gateway;
    private final OrderNotifications notifications;
    private final Set<String> finalFailurePairs;

    public CheckoutSessionService(UserRepository users, CartRepository carts, CartItemRepository cartItems,
            ProductRepository products, CheckoutSessionRepository sessions, CheckoutSessionItemRepository sessionItems,
            PaymentAttemptRepository attempts, OrderRepository orders, OrderItemRepository orderItems,
            PaymentRepository payments, EntityManager em, CheckoutSessionSettings timing, VnpaySettings vnpay,
            VnpayGateway gateway, OrderNotifications notifications,
            @Value("${teasmart.vnpay.verified-final-failure-pairs:}") String pairs) {
        this.users = users; this.carts = carts; this.cartItems = cartItems; this.products = products;
        this.sessions = sessions; this.sessionItems = sessionItems; this.attempts = attempts;
        this.orders = orders; this.orderItems = orderItems; this.payments = payments; this.em = em;
        this.timing = timing; this.vnpay = vnpay; this.gateway = gateway; this.notifications = notifications;
        finalFailurePairs = Arrays.stream(pairs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (finalFailurePairs.stream().anyMatch(s -> !s.matches("(09|10|11|12|13|24|51|65|75|79):02")))
            throw new IllegalArgumentException("Invalid final failure policy.");
    }

    @Transactional
    public CheckoutSessionResponse create(Long userId, CreateCheckoutSessionRequest request, String rawKey) {
        String key = CheckoutIdentity.key(rawKey, true);
        validateSource(request);
        String hash = CheckoutIdentity.sessionHash(request);
        User user = users.findLockedByUserId(userId).orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus()) || !"CUSTOMER".equals(user.getRole()))
            throw new BadCredentialsException("Authentication failed.");
        CheckoutSession existing = sessions.findLockedByUser_UserIdAndIdempotencyKey(userId, key).orElse(null);
        if (existing != null) {
            if (!hash.equals(existing.getRequestHash())) throw PaymentException.conflict("IDEMPOTENCY_KEY_REUSED");
            return view(existing);
        }

        Map<Long, SourceLine> selected = resolveLines(userId, request);
        if (selected.isEmpty()) throw PaymentException.invalid();
        boolean confirmedIndependentBuyNow = request.independentPurchaseIntentId() != null;
        if (confirmedIndependentBuyNow) {
            try { UUID.fromString(request.independentPurchaseIntentId()); }
            catch (IllegalArgumentException invalid) { throw PaymentException.invalid(); }
            if (!"BUY_NOW".equals(request.sourceType())) throw PaymentException.invalid();
        }
        List<CheckoutSessionStatus> unresolvedSessionStates = List.of(CheckoutSessionStatus.AWAITING_PAYMENT,
                CheckoutSessionStatus.VERIFYING, CheckoutSessionStatus.RECONCILIATION_REQUIRED);
        for (CheckoutSession unresolved : sessions.findTop20ByUser_UserIdAndStatusInOrderByCreatedAtDesc(userId,
                unresolvedSessionStates)) {
            List<PaymentAttempt> unresolvedAttempts = attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(
                    unresolved.getCheckoutSessionId());
            if (unresolvedAttempts.stream().noneMatch(a -> a.getStatus() == PaymentAttemptStatus.PENDING
                    || a.getStatus() == PaymentAttemptStatus.UNKNOWN)) continue;
            boolean repeatsUnresolvedProduct = sessionItems.findByCheckoutSession_CheckoutSessionIdOrderByProduct_ProductId(
                    unresolved.getCheckoutSessionId()).stream()
                    .anyMatch(item -> selected.containsKey(item.getProduct().getProductId()));
            if (repeatsUnresolvedProduct && !confirmedIndependentBuyNow)
                throw PaymentException.conflict("PRODUCT_PAYMENT_STATUS_UNRESOLVED");
        }
        var shop = shopScope == null ? null : shopScope.resolve(selected.keySet());
        Map<Long, Product> locked = new TreeMap<>();
        Map<Long, BigDecimal> prices = new TreeMap<>();
        BigDecimal subtotal = ZERO;
        for (Map.Entry<Long, SourceLine> entry : selected.entrySet()) {
            Product product = products.findLockedByProductId(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
            requirePurchasable(product, entry.getValue().quantity(), shop != null && shop.id() != null);
            BigDecimal price = amount(product.getPrice());
            if (price.signum() <= 0) throw PaymentException.invalid();
            BigDecimal line = amount(price.multiply(BigDecimal.valueOf(entry.getValue().quantity())));
            subtotal = amount(subtotal.add(line));
            locked.put(entry.getKey(), product);
            prices.put(entry.getKey(), price);
        }

        LocalDateTime now = PaymentTime.now();
        CheckoutSession session = new CheckoutSession();
        session.setSessionCode("TSCS" + UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        session.setIdempotencyKey(key);
        session.setRequestHash(hash);
        session.setSourceType(request.sourceType());
        session.setRecipientName(request.shipping().recipientName());
        session.setRecipientPhone(request.shipping().recipientPhone());
        session.setShippingAddress(request.shipping().shippingAddress());
        session.setNote(request.shipping().note());
        session.setSubtotal(subtotal);
        session.setShippingFee(ZERO);
        session.setTotalAmount(amount(subtotal.add(ZERO)));
        session.setStatus(CheckoutSessionStatus.READY);
        session.setReservationStatus(ReservationStatus.HELD);
        session.setExpiresAt(now.plusMinutes(timing.sessionTtlMinutes()));
        session.setReservationExpiresAt(now.plusMinutes(timing.reservationTtlMinutes()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessions.saveAndFlush(session);
        if(shopScope!=null)shopScope.saveSession(session.getCheckoutSessionId(),shop);

        for (Map.Entry<Long, SourceLine> entry : selected.entrySet()) {
            Product product = locked.get(entry.getKey());
            SourceLine line = entry.getValue();
            CheckoutSessionItem snapshot = new CheckoutSessionItem();
            snapshot.setCheckoutSession(session);
            snapshot.setProduct(product);
            snapshot.setSourceCartItemId(line.cartItemId());
            snapshot.setProductName(product.getName());
            snapshot.setUnitPrice(prices.get(entry.getKey()));
            snapshot.setQuantity(line.quantity());
            snapshot.setSubtotal(amount(prices.get(entry.getKey()).multiply(BigDecimal.valueOf(line.quantity()))));
            sessionItems.save(snapshot);
            product.setStockQuantity(product.getStockQuantity() - line.quantity());
            product.setUpdatedAt(now);
        }
        em.flush();
        return view(session);
    }

    @Transactional
    public CheckoutSessionActionResponse startVnpay(Long userId, Long sessionId, String rawKey, String ip) {
        String key = CheckoutIdentity.key(rawKey, true);
        vnpay.requireConfigured();
        CheckoutSession session = requireOwnedLocked(userId, sessionId);
        LocalDateTime now = PaymentTime.now();
        if (session.getExpiresAt().isBefore(now) || session.getReservationStatus() != ReservationStatus.HELD)
            throw PaymentException.conflict("CHECKOUT_SESSION_EXPIRED");
        PaymentAttempt repeated = attempts.findByCheckoutSession_CheckoutSessionIdAndIdempotencyKey(sessionId, key).orElse(null);
        if (repeated != null) {
            if (repeated.getStatus() != PaymentAttemptStatus.PENDING || repeated.getExpiresAt() == null
                    || !repeated.getExpiresAt().isAfter(now)) throw PaymentException.conflict("PAYMENT_ATTEMPT_NOT_REPLAYABLE");
            return new CheckoutSessionActionResponse(view(session), paymentUrl(repeated, ip),
                    repeated.getMerchantReference(), repeated.getExpiresAt(), true);
        }
        List<PaymentAttempt> previousAttempts = attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId);
        if (previousAttempts.stream().anyMatch(a -> a.getStatus() != PaymentAttemptStatus.FAILED_FINAL)) {
            boolean paid = previousAttempts.stream().anyMatch(a -> a.getStatus() == PaymentAttemptStatus.SUCCEEDED);
            throw PaymentException.conflict(paid ? "PAYMENT_ALREADY_SUCCEEDED" : "PAYMENT_STATUS_UNRESOLVED");
        }

        List<CheckoutSessionItem> lines = sessionItems.findByCheckoutSession_CheckoutSessionIdOrderByProduct_ProductId(sessionId);
        if (lines.isEmpty()) throw PaymentException.conflict("CHECKOUT_SESSION_EMPTY");
        PaymentAttempt previous = previousAttempts.isEmpty() ? null : previousAttempts.get(previousAttempts.size() - 1);
        int number = previous == null ? 1 : previous.getAttemptNumber() + 1;
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setCheckoutSession(session);
        attempt.setAttemptNumber(number);
        attempt.setGateway("VNPAY");
        attempt.setMerchantReference("TSVNP" + UUID.randomUUID().toString().replace("-", ""));
        attempt.setIdempotencyKey(key);
        attempt.setAmount(session.getTotalAmount());
        attempt.setStatus(PaymentAttemptStatus.PENDING);
        attempt.setReconciliationRequired(false);
        attempt.setCreatedAt(now);
        attempt.setUpdatedAt(now);
        LocalDateTime attemptExpiry = now.plusMinutes(vnpay.expiryMinutes());
        attempt.setExpiresAt(attemptExpiry.isBefore(session.getExpiresAt()) ? attemptExpiry : session.getExpiresAt());
        attempts.saveAndFlush(attempt);
        session.setStatus(CheckoutSessionStatus.AWAITING_PAYMENT);
        session.setUpdatedAt(now);
        em.flush();
        return new CheckoutSessionActionResponse(view(session), paymentUrl(attempt, ip),
                attempt.getMerchantReference(), attempt.getExpiresAt(), false);
    }

    @Transactional
    public CheckoutSessionResponse confirmCod(Long userId, Long sessionId) {
        CheckoutSession session = requireOwnedLocked(userId, sessionId);
        Order existing = orders.findByCheckoutSession_CheckoutSessionId(sessionId).orElse(null);
        if (existing != null) return view(session);
        if (session.getStatus() == CheckoutSessionStatus.PAID_PENDING_ORDER
                || session.getStatus() == CheckoutSessionStatus.VERIFYING
                || session.getStatus() == CheckoutSessionStatus.RECONCILIATION_REQUIRED)
            throw PaymentException.conflict("PAYMENT_STATUS_UNRESOLVED");
        if (!session.getExpiresAt().isAfter(PaymentTime.now()) || session.getReservationStatus() != ReservationStatus.HELD)
            throw PaymentException.conflict("CHECKOUT_SESSION_EXPIRED");
        List<PaymentAttempt> previousAttempts = attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId);
        if (previousAttempts.stream().anyMatch(a -> a.getStatus() != PaymentAttemptStatus.FAILED_FINAL))
            throw PaymentException.conflict("PAYMENT_STATUS_UNRESOLVED");
        Order order = createOfficialOrder(session, PaymentMethod.COD, null, false);
        session.setStatus(CheckoutSessionStatus.COMPLETED);
        session.setReservationStatus(ReservationStatus.CONSUMED);
        session.setUpdatedAt(PaymentTime.now());
        em.flush();
        consumeCartSnapshot(session, userId);
        notifications.placed(order, orderItems.findByOrder_OrderId(order.getOrderId()), payments.findByOrder_OrderId(order.getOrderId()).orElseThrow());
        return view(session);
    }

    @Transactional
    public CheckoutSessionResponse cancel(Long userId, Long sessionId) {
        CheckoutSession session = requireOwnedLocked(userId, sessionId);
        if (session.getStatus() == CheckoutSessionStatus.CANCELLED
                || session.getStatus() == CheckoutSessionStatus.EXPIRED) return view(session);
        if (session.getStatus() == CheckoutSessionStatus.COMPLETED
                || session.getStatus() == CheckoutSessionStatus.PAID_PENDING_ORDER
                || session.getStatus() == CheckoutSessionStatus.VERIFYING
                || session.getStatus() == CheckoutSessionStatus.RECONCILIATION_REQUIRED)
            throw PaymentException.conflict("CHECKOUT_SESSION_NOT_CANCELLABLE");
        List<PaymentAttempt> previousAttempts = attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId);
        if (previousAttempts.stream().anyMatch(a -> a.getStatus() != PaymentAttemptStatus.FAILED_FINAL))
            throw PaymentException.conflict("PAYMENT_STATUS_UNRESOLVED");
        if (session.getReservationStatus() == ReservationStatus.HELD) {
            releaseReservation(session);
            session.setReservationStatus(ReservationStatus.RELEASED);
        }
        session.setStatus(CheckoutSessionStatus.CANCELLED);
        session.setUpdatedAt(PaymentTime.now());
        em.flush();
        return view(session);
    }

    @Transactional(readOnly = true)
    public CheckoutSessionResponse get(Long userId, Long sessionId) {
        CheckoutSession session = sessions.findByCheckoutSessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
        return view(session);
    }

    @Transactional(readOnly = true)
    public List<CheckoutSessionResponse> active(Long userId) {
        return sessions.findTop20ByUser_UserIdAndStatusInOrderByCreatedAtDesc(userId, List.of(
                CheckoutSessionStatus.READY, CheckoutSessionStatus.AWAITING_PAYMENT,
                CheckoutSessionStatus.VERIFYING, CheckoutSessionStatus.PAID_PENDING_ORDER,
                CheckoutSessionStatus.RECONCILIATION_REQUIRED)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public CheckoutPaymentStatusResponse statusByReference(Long userId, String reference) {
        PaymentAttempt attempt = attempts.findByMerchantReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found."));
        if (attempt.getCheckoutSession() == null
                || !attempt.getCheckoutSession().getUser().getUserId().equals(userId))
            throw new ResourceNotFoundException("Payment attempt not found.");
        CheckoutSession session = attempt.getCheckoutSession();
        Order order = orders.findByCheckoutSession_CheckoutSessionId(session.getCheckoutSessionId()).orElse(null);
        return new CheckoutPaymentStatusResponse(session.getCheckoutSessionId(),
                order == null ? null : order.getOrderId(), attempt.getMerchantReference(), attempt.getStatus(),
                session.getStatus(), attempt.getAmount(), attempt.getPaidAt(),
                attempt.isReconciliationRequired() || session.getStatus() == CheckoutSessionStatus.RECONCILIATION_REQUIRED);
    }

    @Transactional(readOnly = true)
    public AttemptSnapshot snapshot(Long attemptId) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found."));
        if (attempt.getCheckoutSession() == null || attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED
                || attempt.getStatus() == PaymentAttemptStatus.FAILED_FINAL)
            throw PaymentException.conflict("PAYMENT_ATTEMPT_NOT_RECONCILABLE");
        return new AttemptSnapshot(attempt.getPaymentAttemptId(), attempt.getCheckoutSession().getCheckoutSessionId(),
                attempt.getMerchantReference(), attempt.getAmount(), attempt.getCreatedAt(), attempt.getExpiresAt(), attempt.getStatus());
    }

    @Transactional
    public void markAttemptUnresolved(Long attemptId) {
        PaymentAttempt attempt = attempts.findLockedByPaymentAttemptId(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found."));
        if (attempt.getCheckoutSession() == null) return;
        CheckoutSession session = sessions.findLockedByCheckoutSessionId(attempt.getCheckoutSession().getCheckoutSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
        if (attempt.getStatus() == PaymentAttemptStatus.PENDING || attempt.getStatus() == PaymentAttemptStatus.UNKNOWN) {
            LocalDateTime now = PaymentTime.now();
            attempt.setStatus(PaymentAttemptStatus.UNKNOWN);
            attempt.setReconciliationRequired(true);
            attempt.setLastReconciliationAt(now);
            attempt.setUpdatedAt(now);
            session.setStatus(CheckoutSessionStatus.VERIFYING);
            session.setUpdatedAt(now);
        }
    }

    /** Called after the verified notification has been durably committed. */
    @Transactional
    public GatewayAcceptance acceptAttempt(VnpayGateway.Notification notification, boolean reconciliation) {
        PaymentAttempt lookup = attempts.findByMerchantReference(notification.reference()).orElse(null);
        if (lookup == null || lookup.getCheckoutSession() == null) return GatewayAcceptance.unmatched();
        Long sessionId = lookup.getCheckoutSession().getCheckoutSessionId();
        CheckoutSession session = sessions.findLockedByCheckoutSessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
        PaymentAttempt attempt = attempts.findLockedByPaymentAttemptId(lookup.getPaymentAttemptId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found."));
        if (!notification.reference().equals(attempt.getMerchantReference())
                || attempt.getAmount().compareTo(notification.amount()) != 0
                || attempt.getAmount().compareTo(session.getTotalAmount()) != 0) return GatewayAcceptance.matched("04", null);

        LocalDateTime now = PaymentTime.now();
        if (notification.success()) {
            if (notification.paidAt() == null || notification.paidAt().isBefore(attempt.getCreatedAt()))
                return GatewayAcceptance.matched("99", null);
            if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
                boolean exactDuplicate = notification.transactionNo().equals(attempt.getTransactionNo());
                if (!exactDuplicate) {
                    attempt.setReconciliationRequired(true);
                    Order paidOrder = orders.findLockedByCheckoutSession_CheckoutSessionId(sessionId).orElse(null);
                    if (paidOrder != null) {
                        payments.findLockedByOrder_OrderId(paidOrder.getOrderId()).ifPresent(payment -> {
                            payment.setReconciliationRequired(true);
                            payment.setUpdatedAt(now);
                        });
                    }
                    session.setStatus(CheckoutSessionStatus.RECONCILIATION_REQUIRED);
                    session.setUpdatedAt(now);
                }
                return GatewayAcceptance.matched(exactDuplicate ? "02" : "00",
                        attempt.isReconciliationRequired() && session.getReservationStatus() != ReservationStatus.HELD
                                ? null : sessionId);
            }
            boolean conflicting = attempt.getStatus() == PaymentAttemptStatus.FAILED_FINAL
                    || attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId).stream()
                            .anyMatch(other -> !other.getPaymentAttemptId().equals(attempt.getPaymentAttemptId())
                                    && other.getStatus() == PaymentAttemptStatus.SUCCEEDED);
            attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
            attempt.setTransactionNo(notification.transactionNo());
            attempt.setGatewayResponseCode(notification.responseCode());
            attempt.setGatewayTransactionStatus(notification.transactionStatus());
            attempt.setPaidAt(notification.paidAt());
            attempt.setReconciliationRequired(conflicting);
            if (reconciliation) attempt.setLastReconciliationAt(now);
            attempt.setUpdatedAt(now);
            if (session.getStatus() != CheckoutSessionStatus.COMPLETED) {
                session.setStatus(conflicting ? CheckoutSessionStatus.RECONCILIATION_REQUIRED
                        : CheckoutSessionStatus.PAID_PENDING_ORDER);
            } else {
                applyLateSuccessToExistingOrder(session, attempt);
            }
            session.setUpdatedAt(now);
            // A valid late charge is evidence for reconciliation, not permission
            // to fulfil a cancelled purchase whose inventory was already released.
            return GatewayAcceptance.matched("00",
                    conflicting && session.getReservationStatus() != ReservationStatus.HELD ? null : sessionId);
        }

        if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            attempt.setReconciliationRequired(true);
            Order paidOrder = orders.findLockedByCheckoutSession_CheckoutSessionId(sessionId).orElse(null);
            if (paidOrder != null) {
                payments.findLockedByOrder_OrderId(paidOrder.getOrderId()).ifPresent(payment -> {
                    payment.setReconciliationRequired(true);
                    payment.setUpdatedAt(now);
                });
            }
            session.setStatus(CheckoutSessionStatus.RECONCILIATION_REQUIRED);
            session.setUpdatedAt(now);
            return GatewayAcceptance.matched("00", null);
        }

        attempt.setGatewayResponseCode(notification.responseCode());
        attempt.setGatewayTransactionStatus(notification.transactionStatus());
        if (reconciliation) attempt.setLastReconciliationAt(now);
        attempt.setUpdatedAt(now);
        boolean verifiedFinal = finalFailurePairs.contains(notification.responseCode() + ":" + notification.transactionStatus())
                || (reconciliation && "00".equals(notification.responseCode())
                    && "02".equals(notification.transactionStatus()));
        if (verifiedFinal) {
            attempt.setStatus(PaymentAttemptStatus.FAILED_FINAL);
            attempt.setReconciliationRequired(false);
            boolean unresolved = attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId)
                    .stream().anyMatch(other -> !other.getPaymentAttemptId().equals(attempt.getPaymentAttemptId())
                            && (other.getStatus() == PaymentAttemptStatus.PENDING || other.getStatus() == PaymentAttemptStatus.UNKNOWN));
            boolean verifiedCancellation = ("24".equals(notification.responseCode())
                    && "02".equals(notification.transactionStatus()))
                    || (reconciliation && "00".equals(notification.responseCode())
                    && "02".equals(notification.transactionStatus()));
            if (session.getStatus() != CheckoutSessionStatus.COMPLETED) {
                if (unresolved) session.setStatus(CheckoutSessionStatus.VERIFYING);
                else if (verifiedCancellation) {
                    if (session.getReservationStatus() == ReservationStatus.HELD) {
                        releaseReservation(session);
                        session.setReservationStatus(ReservationStatus.RELEASED);
                    }
                    session.setStatus(CheckoutSessionStatus.CANCELLED);
                } else if (session.getExpiresAt().isAfter(now)) session.setStatus(CheckoutSessionStatus.READY);
                else {
                    if (session.getReservationStatus() == ReservationStatus.HELD) {
                        releaseReservation(session);
                        session.setReservationStatus(ReservationStatus.RELEASED);
                    }
                    session.setStatus(CheckoutSessionStatus.EXPIRED);
                }
            }
            session.setUpdatedAt(now);
            return GatewayAcceptance.matched("00", null);
        }
        attempt.setStatus(PaymentAttemptStatus.UNKNOWN);
        attempt.setReconciliationRequired(true);
        session.setStatus(CheckoutSessionStatus.VERIFYING);
        session.setUpdatedAt(now);
        return GatewayAcceptance.matched("00", null);
    }

    @Transactional
    public Order finalizePaidSession(Long sessionId) {
        CheckoutSession session = sessions.findLockedByCheckoutSessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
        Order existing = orders.findByCheckoutSession_CheckoutSessionId(sessionId).orElse(null);
        if (existing != null) return existing;
        if (session.getStatus() != CheckoutSessionStatus.PAID_PENDING_ORDER
                && session.getStatus() != CheckoutSessionStatus.RECONCILIATION_REQUIRED)
            throw PaymentException.conflict("CHECKOUT_SESSION_NOT_PAID");
        PaymentAttempt winner = attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId).stream()
                .filter(x -> x.getStatus() == PaymentAttemptStatus.SUCCEEDED)
                .min(Comparator.comparing(PaymentAttempt::getAttemptNumber)).orElseThrow(() -> PaymentException.conflict("PAYMENT_NOT_VERIFIED"));
        boolean conflict = winner.isReconciliationRequired()
                || attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId).stream()
                    .filter(x -> x.getStatus() == PaymentAttemptStatus.SUCCEEDED).count() > 1;
        if (conflict && session.getReservationStatus() != ReservationStatus.HELD)
            throw PaymentException.conflict("PAYMENT_CONFLICT_REQUIRES_RECONCILIATION");
        Order order = createOfficialOrder(session, PaymentMethod.ONLINE, winner, conflict);
        session.setStatus(CheckoutSessionStatus.COMPLETED);
        session.setReservationStatus(order.getCheckoutSession() == session
                ? (conflict && session.getReservationStatus() != ReservationStatus.HELD
                    ? ReservationStatus.RECONCILIATION_REQUIRED : ReservationStatus.CONSUMED)
                : ReservationStatus.RECONCILIATION_REQUIRED);
        session.setUpdatedAt(PaymentTime.now());
        em.flush();
        consumeCartSnapshot(session, session.getUser().getUserId());
        Payment payment = payments.findByOrder_OrderId(order.getOrderId()).orElseThrow();
        notifications.placed(order, orderItems.findByOrder_OrderId(order.getOrderId()), payment);
        notifications.payment(order, payment);
        return order;
    }

    @Transactional
    public void expireReservation(Long sessionId) {
        CheckoutSession session = sessions.findLockedByCheckoutSessionId(sessionId).orElse(null);
        if (session == null || session.getReservationStatus() != ReservationStatus.HELD
                || session.getReservationExpiresAt().isAfter(PaymentTime.now())) return;
        if (session.getStatus() == CheckoutSessionStatus.PAID_PENDING_ORDER) return;
        List<PaymentAttempt> rows = attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(sessionId);
        boolean uncertain = rows.stream().anyMatch(a -> a.getStatus() == PaymentAttemptStatus.PENDING
                || a.getStatus() == PaymentAttemptStatus.UNKNOWN);
        if (uncertain) {
            session.setStatus(CheckoutSessionStatus.RECONCILIATION_REQUIRED);
            session.setUpdatedAt(PaymentTime.now());
            return;
        }
        releaseReservation(session);
        session.setStatus(CheckoutSessionStatus.EXPIRED);
        session.setReservationStatus(ReservationStatus.RELEASED);
        session.setUpdatedAt(PaymentTime.now());
    }

    @Transactional(readOnly = true)
    public List<Long> expiredReservationCandidates() {
        return sessions.findExpiredReservationCandidates(PaymentTime.now(), List.of(CheckoutSessionStatus.READY,
                CheckoutSessionStatus.AWAITING_PAYMENT, CheckoutSessionStatus.VERIFYING), PageRequest.of(0, 100));
    }

    @Transactional(readOnly = true)
    public Optional<Long> latestUnresolvedAttempt(Long userId, Long sessionId) {
        CheckoutSession session = sessions.findByCheckoutSessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
        return attempts.findByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberAsc(session.getCheckoutSessionId()).stream()
                .filter(a -> a.getStatus() == PaymentAttemptStatus.PENDING || a.getStatus() == PaymentAttemptStatus.UNKNOWN)
                .map(PaymentAttempt::getPaymentAttemptId).reduce((first, second) -> second);
    }

    @Transactional(readOnly = true)
    public List<CheckoutSessionResponse> reconciliationQueue() {
        return sessions.findTop100ByStatusInOrderByUpdatedAtAsc(reconciliationQueueStatuses())
                .stream().map(this::view).toList();
    }

    static List<CheckoutSessionStatus> reconciliationQueueStatuses() {
        return List.of(CheckoutSessionStatus.RECONCILIATION_REQUIRED,
                CheckoutSessionStatus.PAID_PENDING_ORDER, CheckoutSessionStatus.VERIFYING);
    }

    public record AttemptSnapshot(Long attemptId, Long sessionId, String reference, BigDecimal amount,
            LocalDateTime createdAt, LocalDateTime expiresAt, PaymentAttemptStatus status) { }
    public record GatewayAcceptance(boolean matched, String responseCode, Long finalizeSessionId) {
        public static GatewayAcceptance unmatched() { return new GatewayAcceptance(false, "", null); }
        static GatewayAcceptance matched(String code, Long sessionId) { return new GatewayAcceptance(true, code, sessionId); }
    }

    private Map<Long, SourceLine> resolveLines(Long userId, CreateCheckoutSessionRequest request) {
        Map<Long, SourceLine> result = new TreeMap<>();
        switch (request.sourceType()) {
            case "CART" -> {
                requireEmpty(request.buyNowProductId(), request.buyNowQuantity(), request.selectedItems());
                Cart cart = carts.findByUser_UserId(userId).orElseThrow(() -> PaymentException.conflict("CART_EMPTY"));
                for (CartItem item : cartItems.findByCart_CartId(cart.getCartId())) {
                    addLine(result, item.getProduct().getProductId(), item.getQuantity(), item.getCartItemId());
                    em.detach(item.getProduct());
                }
            }
            case "SELECTED" -> {
                if (request.selectedItems() == null || request.selectedItems().isEmpty()
                        || request.buyNowProductId() != null || request.buyNowQuantity() != null) throw PaymentException.invalid();
                Cart cart = carts.findByUser_UserId(userId).orElseThrow(() -> PaymentException.conflict("CART_EMPTY"));
                Map<Long, CartItem> owned = new TreeMap<>();
                for (CartItem item : cartItems.findByCart_CartId(cart.getCartId())) owned.put(item.getCartItemId(), item);
                for (var selection : request.selectedItems().stream().sorted(Comparator.comparing(
                        vn.teasmart.backend.dto.request.SelectedCheckoutRequest.Item::cartItemId)).toList()) {
                    CartItem item = owned.get(selection.cartItemId());
                    if (item == null || !selection.quantity().equals(item.getQuantity()))
                        throw new OrderConflictException("CART_SELECTION_CHANGED", "Selected cart items or quantities changed.");
                    addLine(result, item.getProduct().getProductId(), item.getQuantity(), item.getCartItemId());
                    em.detach(item.getProduct());
                }
            }
            case "BUY_NOW" -> {
                if (request.buyNowProductId() == null || request.buyNowProductId() <= 0
                        || request.buyNowQuantity() == null || request.buyNowQuantity() <= 0
                        || (request.selectedItems() != null && !request.selectedItems().isEmpty())
                        || (request.buyAgainItems() != null && !request.buyAgainItems().isEmpty())) throw PaymentException.invalid();
                addLine(result, request.buyNowProductId(), request.buyNowQuantity(), null);
            }
            case "BUY_AGAIN" -> {
                if (request.buyNowProductId() != null || request.buyNowQuantity() != null
                        || (request.selectedItems() != null && !request.selectedItems().isEmpty())
                        || request.buyAgainItems() == null || request.buyAgainItems().isEmpty()) throw PaymentException.invalid();
                for (var item : request.buyAgainItems().stream().sorted(Comparator.comparing(
                        CreateCheckoutSessionRequest.BuyAgainItem::productId)).toList()) {
                    if (item == null) throw PaymentException.invalid();
                    addLine(result, item.productId(), item.quantity(), null);
                }
            }
            default -> throw PaymentException.invalid();
        }
        return result;
    }

    private static void validateSource(CreateCheckoutSessionRequest request) {
        if (request == null || request.shipping() == null || request.sourceType() == null) throw PaymentException.invalid();
        if ("CART".equals(request.sourceType()) && (request.buyNowProductId() != null || request.buyNowQuantity() != null
                || (request.selectedItems() != null && !request.selectedItems().isEmpty())
                || (request.buyAgainItems() != null && !request.buyAgainItems().isEmpty()))) throw PaymentException.invalid();
        if ("SELECTED".equals(request.sourceType()) && (request.selectedItems() == null || request.selectedItems().isEmpty()
                || request.selectedItems().stream().anyMatch(x -> x == null || x.cartItemId() == null || x.cartItemId() < 1
                        || x.quantity() == null || x.quantity() < 1)
                || request.buyNowProductId() != null || request.buyNowQuantity() != null
                || (request.buyAgainItems() != null && !request.buyAgainItems().isEmpty()))) throw PaymentException.invalid();
        if ("BUY_NOW".equals(request.sourceType()) && (request.buyNowProductId() == null || request.buyNowProductId() < 1
                || request.buyNowQuantity() == null || request.buyNowQuantity() < 1
                || (request.selectedItems() != null && !request.selectedItems().isEmpty())
                || (request.buyAgainItems() != null && !request.buyAgainItems().isEmpty()))) throw PaymentException.invalid();
        if ("BUY_AGAIN".equals(request.sourceType()) && (request.buyNowProductId() != null || request.buyNowQuantity() != null
                || (request.selectedItems() != null && !request.selectedItems().isEmpty())
                || request.buyAgainItems() == null || request.buyAgainItems().isEmpty()
                || request.buyAgainItems().stream().anyMatch(x -> x == null || x.productId() == null || x.productId() < 1
                        || x.quantity() == null || x.quantity() < 1)
                || request.buyAgainItems().stream().map(CreateCheckoutSessionRequest.BuyAgainItem::productId).distinct().count() != request.buyAgainItems().size())) throw PaymentException.invalid();
        if (!Set.of("CART", "SELECTED", "BUY_NOW", "BUY_AGAIN").contains(request.sourceType())) throw PaymentException.invalid();
    }

    private static void requireEmpty(Long productId, Long quantity, List<?> selected) {
        if (productId != null || quantity != null || (selected != null && !selected.isEmpty())) throw PaymentException.invalid();
    }

    private static void addLine(Map<Long, SourceLine> rows, Long productId, Long quantity, Long cartItemId) {
        if (productId == null || productId < 1 || quantity == null || quantity < 1
                || rows.putIfAbsent(productId, new SourceLine(quantity, cartItemId)) != null) throw PaymentException.invalid();
    }

    private void requirePurchasable(Product product, Long quantity, boolean sellerShop) {
        boolean catalogActive = "ACTIVE".equals(product.getStatus());
        boolean sellerListingActive = sellerShop && shopScope != null
                && shopScope.activeSellerListing(product.getProductId());
        if (!catalogActive && !sellerListingActive)
            throw new OrderConflictException("PRODUCT_UNAVAILABLE", "Product cannot be purchased.");
        Long stock = product.getStockQuantity();
        if (stock == null || stock < 0 || stock > MAX_STOCK) throw PaymentException.invalid();
        if (quantity == null || quantity < 1 || quantity > MAX_STOCK) throw PaymentException.invalid();
        if (stock < quantity) throw new OrderConflictException("INSUFFICIENT_STOCK", "Insufficient stock.");
    }

    private static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0) throw PaymentException.invalid();
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private CheckoutSession requireOwnedLocked(Long userId, Long sessionId) {
        CheckoutSession session = sessions.findLockedByCheckoutSessionIdAndUser_UserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Checkout session not found."));
        if (!"ACTIVE".equals(session.getUser().getStatus()) || !"CUSTOMER".equals(session.getUser().getRole()))
            throw new BadCredentialsException("Authentication failed.");
        return session;
    }

    private String paymentUrl(PaymentAttempt attempt, String ip) {
        Payment payment = new Payment();
        payment.setMerchantReference(attempt.getMerchantReference());
        payment.setAmount(attempt.getAmount());
        payment.setCreatedAt(attempt.getCreatedAt());
        payment.setExpiresAt(attempt.getExpiresAt());
        return gateway.paymentUrl(payment, ip);
    }

    private Order createOfficialOrder(CheckoutSession session, PaymentMethod method, PaymentAttempt winningAttempt,
            boolean reconciliation) {
        Order existing = orders.findByCheckoutSession_CheckoutSessionId(session.getCheckoutSessionId()).orElse(null);
        if (existing != null) return existing;
        List<CheckoutSessionItem> snapshots = sessionItems.findByCheckoutSession_CheckoutSessionIdOrderByProduct_ProductId(session.getCheckoutSessionId());
        if (snapshots.isEmpty()) throw PaymentException.conflict("CHECKOUT_SESSION_EMPTY");
        boolean held = session.getReservationStatus() == ReservationStatus.HELD;
        boolean availableAfterRelease = true;
        Map<Long, Product> lockedProducts = new TreeMap<>();
        if (!held) {
            for (CheckoutSessionItem item : snapshots) {
                Product p = products.findLockedByProductId(item.getProduct().getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
                lockedProducts.put(p.getProductId(), p);
                if (p.getStockQuantity() < item.getQuantity()) availableAfterRelease = false;
            }
            if (availableAfterRelease) {
                for (CheckoutSessionItem item : snapshots) {
                    Product p = lockedProducts.get(item.getProduct().getProductId());
                    p.setStockQuantity(p.getStockQuantity() - item.getQuantity());
                    p.setUpdatedAt(PaymentTime.now());
                }
            } else reconciliation = true;
        }
        LocalDateTime now = PaymentTime.now();
        Order order = new Order();
        order.setCheckoutSession(session);
        order.setOrderCode("TS-" + UUID.randomUUID().toString().replace("-", ""));
        order.setUser(session.getUser());
        order.setRecipientName(session.getRecipientName());
        order.setRecipientPhone(session.getRecipientPhone());
        order.setShippingAddress(session.getShippingAddress());
        order.setNote(session.getNote());
        order.setSubtotal(session.getSubtotal());
        order.setShippingFee(session.getShippingFee());
        order.setTotalAmount(session.getTotalAmount());
        order.setOrderStatus("PENDING");
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orders.saveAndFlush(order);
        if(shopScope!=null)shopScope.saveOrder(order.getOrderId(),shopScope.session(session.getCheckoutSessionId()));
        for (CheckoutSessionItem snapshot : snapshots) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(snapshot.getProduct());
            item.setProductName(snapshot.getProductName());
            item.setUnitPrice(snapshot.getUnitPrice());
            item.setQuantity(snapshot.getQuantity());
            item.setSubtotal(snapshot.getSubtotal());
            orderItems.save(item);
        }
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setPaymentMethod(method);
        payment.setPaymentStatus(method == PaymentMethod.COD ? PaymentStatus.PENDING : PaymentStatus.PAID);
        payment.setGateway(method == PaymentMethod.COD ? null : "VNPAY");
        payment.setAmount(session.getTotalAmount());
        payment.setReconciliationRequired(reconciliation);
        if (winningAttempt != null) {
            payment.setMerchantReference(winningAttempt.getMerchantReference());
            payment.setGatewayResponseCode(winningAttempt.getGatewayResponseCode());
            payment.setGatewayTransactionStatus(winningAttempt.getGatewayTransactionStatus());
            payment.setTransactionCode("VNPAY:" + winningAttempt.getTransactionNo());
            payment.setPaidAt(winningAttempt.getPaidAt());
            payment.setLastReconciliationAt(winningAttempt.getLastReconciliationAt());
        }
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);
        payments.save(payment);
        return order;
    }

    private void applyLateSuccessToExistingOrder(CheckoutSession session, PaymentAttempt attempt) {
        Order order = orders.findLockedByCheckoutSession_CheckoutSessionId(session.getCheckoutSessionId()).orElse(null);
        if (order == null) {
            session.setStatus(CheckoutSessionStatus.PAID_PENDING_ORDER);
            return;
        }
        Payment payment = payments.findLockedByOrder_OrderId(order.getOrderId()).orElse(null);
        if (payment == null) {
            session.setStatus(CheckoutSessionStatus.RECONCILIATION_REQUIRED);
            return;
        }
        payment.setReconciliationRequired(true);
        if (payment.getPaymentStatus() != PaymentStatus.PAID) {
            payment.setPaymentMethod(PaymentMethod.ONLINE);
            payment.setGateway("VNPAY");
            payment.setMerchantReference(attempt.getMerchantReference());
            payment.setTransactionCode("VNPAY:" + attempt.getTransactionNo());
            payment.setPaidAt(attempt.getPaidAt());
            payment.setPaymentStatus(PaymentStatus.PAID);
            payment.setGatewayResponseCode(attempt.getGatewayResponseCode());
            payment.setGatewayTransactionStatus(attempt.getGatewayTransactionStatus());
        }
        payment.setUpdatedAt(PaymentTime.now());
        session.setStatus(CheckoutSessionStatus.RECONCILIATION_REQUIRED);
    }

    private void releaseReservation(CheckoutSession session) {
        List<CheckoutSessionItem> lines = sessionItems.findByCheckoutSession_CheckoutSessionIdOrderByProduct_ProductId(session.getCheckoutSessionId());
        if (lines.isEmpty()) throw PaymentException.conflict("CHECKOUT_SESSION_EMPTY");
        Map<Long, Long> quantities = new TreeMap<>();
        for (CheckoutSessionItem line : lines) quantities.merge(line.getProduct().getProductId(), line.getQuantity(), Math::addExact);
        Map<Long, Product> locked = new TreeMap<>();
        for (Long id : quantities.keySet()) {
            Product product = products.findLockedByProductId(id).orElseThrow(() -> new ResourceNotFoundException("Product not found."));
            if (product.getStockQuantity() > MAX_STOCK - quantities.get(id)) throw PaymentException.conflict("STOCK_OVERFLOW");
            locked.put(id, product);
        }
        LocalDateTime now = PaymentTime.now();
        for (Map.Entry<Long, Product> entry : locked.entrySet()) {
            Product product = entry.getValue();
            product.setStockQuantity(product.getStockQuantity() + quantities.get(entry.getKey()));
            product.setUpdatedAt(now);
        }
    }

    private void consumeCartSnapshot(CheckoutSession session, Long userId) {
        if (!("CART".equals(session.getSourceType()) || "SELECTED".equals(session.getSourceType()))) return;
        for (CheckoutSessionItem item : sessionItems.findByCheckoutSession_CheckoutSessionIdOrderByProduct_ProductId(session.getCheckoutSessionId())) {
            if (item.getSourceCartItemId() != null) cartItems.deleteSnapshotMatch(item.getSourceCartItemId(), userId,
                    item.getProduct().getProductId(), item.getQuantity());
        }
    }

    private CheckoutSessionResponse view(CheckoutSession session) {
        List<CheckoutSessionResponse.Item> items = sessionItems
                .findByCheckoutSession_CheckoutSessionIdOrderByProduct_ProductId(session.getCheckoutSessionId()).stream()
                .map(i -> new CheckoutSessionResponse.Item(i.getProduct().getProductId(), i.getProductName(),
                        i.getUnitPrice(), i.getQuantity(), i.getSubtotal())).toList();
        PaymentAttempt latest = attempts.findFirstByCheckoutSession_CheckoutSessionIdOrderByAttemptNumberDesc(
                session.getCheckoutSessionId()).orElse(null);
        Order order = orders.findByCheckoutSession_CheckoutSessionId(session.getCheckoutSessionId()).orElse(null);
        var shop = shopScope==null ? vn.teasmart.backend.seller.CheckoutShopScope.LEGACY : shopScope.session(session.getCheckoutSessionId());
        return new CheckoutSessionResponse(session.getCheckoutSessionId(), session.getSessionCode(), session.getStatus(),
                session.getReservationStatus(), session.getExpiresAt(), session.getReservationExpiresAt(),
                session.getRecipientName(), session.getRecipientPhone(), session.getShippingAddress(), session.getNote(),
                session.getSubtotal(), session.getShippingFee(), session.getTotalAmount(),
                latest == null ? null : latest.getStatus(), latest == null ? null : latest.getMerchantReference(),
                latest == null ? null : latest.getAttemptNumber(),
                session.getStatus() == CheckoutSessionStatus.RECONCILIATION_REQUIRED
                        || (latest != null && latest.isReconciliationRequired()),
                order == null ? null : order.getOrderId(), items,shop.id(),shop.name());
    }

    private static void addLine(Map<Long, SourceLine> rows, Long productId, Long quantity, Long cartId, boolean ignored) {
        addLine(rows, productId, quantity, cartId);
    }

    private record SourceLine(Long quantity, Long cartItemId) { }
}
