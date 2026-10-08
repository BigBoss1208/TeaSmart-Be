package vn.teasmart.backend.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AddCartItemRequest;
import vn.teasmart.backend.dto.request.UpdateCartItemRequest;
import vn.teasmart.backend.dto.response.CartItemResponse;
import vn.teasmart.backend.dto.response.CartResponse;
import vn.teasmart.backend.entity.Cart;
import vn.teasmart.backend.entity.CartItem;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.exception.CartConflictException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.CartItemRepository;
import vn.teasmart.backend.repository.CartRepository;
import vn.teasmart.backend.repository.ProductRepository;
import vn.teasmart.backend.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class CartService {
    private static final String PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE";
    private static final String OUT_OF_STOCK = "OUT_OF_STOCK";
    private static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    private final CartRepository cartRepository;
    private final CartItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public CartService(CartRepository cartRepository, CartItemRepository itemRepository,
            ProductRepository productRepository, UserRepository userRepository) {
        this.cartRepository = cartRepository;
        this.itemRepository = itemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    public CartResponse getCart(Long userId) {
        return cartRepository.findByUser_UserId(userId).map(this::toResponse)
                .orElseGet(() -> new CartResponse(null, List.of(), 0L, ZERO_AMOUNT));
    }

    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest request) {
        User user = lockCustomer(userId);
        Cart cart = cartRepository.findByUser_UserId(userId).orElse(null);
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
        CartItem item = cart == null ? null : itemRepository
                .findByCart_CartIdAndProduct_ProductId(cart.getCartId(), product.getProductId()).orElse(null);
        // Both quantities fit INT UNSIGNED, so this addition cannot overflow Java Long.
        long quantity = request.quantity() + (item == null ? 0L : item.getQuantity());
        requirePurchasable(product, quantity);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (cart == null) {
            cart = new Cart();
            cart.setUser(user);
            cart.setCreatedAt(now);
            cart.setUpdatedAt(now);
            cartRepository.saveAndFlush(cart);
        }
        if (item == null) {
            item = new CartItem();
            item.setCart(cart);
            item.setProduct(product);
            item.setCreatedAt(now);
        }
        item.setQuantity(quantity);
        item.setUpdatedAt(now);
        itemRepository.saveAndFlush(item);
        cart.setUpdatedAt(now);
        cartRepository.saveAndFlush(cart);
        return toResponse(cart);
    }

    @Transactional
    public CartResponse updateItem(Long userId, Long cartItemId, UpdateCartItemRequest request) {
        lockCustomer(userId);
        CartItem item = requireOwnedItem(userId, cartItemId);
        requirePurchasable(item.getProduct(), request.quantity());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        item.setQuantity(request.quantity());
        item.setUpdatedAt(now);
        itemRepository.saveAndFlush(item);
        Cart cart = item.getCart();
        cart.setUpdatedAt(now);
        cartRepository.saveAndFlush(cart);
        return toResponse(cart);
    }

    @Transactional
    public void deleteItem(Long userId, Long cartItemId) {
        lockCustomer(userId);
        CartItem item = requireOwnedItem(userId, cartItemId);
        Cart cart = item.getCart();
        itemRepository.delete(item);
        cart.setUpdatedAt(LocalDateTime.now().withNano(0));
        cartRepository.saveAndFlush(cart);
    }

    private User lockCustomer(Long userId) {
        // Always lock the existing User first, even before the first Cart is created.
        User user = userRepository.findLockedByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        if (!"ACTIVE".equals(user.getStatus()) || !"CUSTOMER".equals(user.getRole())) {
            throw new BadCredentialsException("Authentication failed.");
        }
        return user;
    }

    private CartItem requireOwnedItem(Long userId, Long cartItemId) {
        return itemRepository.findByCartItemIdAndCart_User_UserId(cartItemId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found."));
    }

    private String availabilityReason(Product product, long quantity) {
        if (!"ACTIVE".equals(product.getStatus()) || !"ACTIVE".equals(product.getCategory().getStatus())
                || !"ACTIVE".equals(product.getRegion().getStatus()) || !"ACTIVE".equals(product.getStore().getStatus())) {
            return PRODUCT_UNAVAILABLE;
        }
        if (product.getStockQuantity() == 0) {
            return OUT_OF_STOCK;
        }
        return quantity > product.getStockQuantity() ? INSUFFICIENT_STOCK : null;
    }

    private void requirePurchasable(Product product, long quantity) {
        String reason = availabilityReason(product, quantity);
        if (reason != null) {
            String message = switch (reason) {
                case PRODUCT_UNAVAILABLE -> "Product is unavailable.";
                case OUT_OF_STOCK -> "Product is out of stock.";
                default -> "Requested quantity exceeds current stock.";
            };
            throw new CartConflictException(reason, message);
        }
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = itemRepository.findByCart_CartId(cart.getCartId()).stream()
                .sorted(Comparator.comparing(CartItem::getCartItemId)).map(this::toItemResponse).toList();
        long totalItems = items.stream().mapToLong(CartItemResponse::quantity).sum();
        BigDecimal totalAmount = items.stream().map(CartItemResponse::subtotal).reduce(ZERO_AMOUNT, BigDecimal::add);
        return new CartResponse(cart.getCartId(), items, totalItems, totalAmount);
    }

    private CartItemResponse toItemResponse(CartItem item) {
        Product product = item.getProduct();
        String reason = availabilityReason(product, item.getQuantity());
        BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(item.getCartItemId(), product.getProductId(), product.getName(), product.getSlug(),
                product.getImageUrl(), product.getPrice(), item.getQuantity(), subtotal, product.getStockQuantity(),
                reason == null, reason);
    }
}
