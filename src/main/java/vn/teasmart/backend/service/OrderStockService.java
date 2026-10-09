package vn.teasmart.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.exception.OrderConflictException;

@Service
public class OrderStockService {
    private final OrderItemRepository orderItems;
    private final ProductRepository products;
    private final EntityManager entityManager;
    public OrderStockService(OrderItemRepository orderItems, ProductRepository products, EntityManager entityManager) {
        this.orderItems = orderItems; this.products = products; this.entityManager = entityManager;
    }
    // Caller owns the Order lock and must commit status and stock in this transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Order order) {
        if ("CANCELLED".equals(order.getOrderStatus()) || order.getStockReleasedAt() != null)
            throw new OrderConflictException("STOCK_ALREADY_RELEASED", "Order stock must not be released twice.");
        if (!"PENDING".equals(order.getOrderStatus()))
            throw new OrderConflictException("ORDER_NOT_CANCELLABLE", "Only pending orders may release stock.");
        List<OrderItem> items = orderItems.findByOrder_OrderId(order.getOrderId());
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
        LocalDateTime now = PaymentTime.now();
        for (Map.Entry<Long, Product> entry : lockedProducts.entrySet()) {
            Product product = entry.getValue();
            product.setStockQuantity(product.getStockQuantity() + quantities.get(entry.getKey()));
            product.setUpdatedAt(now);
        }
        order.setStockReleasedAt(now);
    }
    private OrderConflictException invalidOrderItems() {
        return new OrderConflictException("INVALID_ORDER_ITEMS", "Order items contain invalid product data.");
    }
}
