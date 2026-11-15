package vn.teasmart.backend.service;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.BadCredentialsException;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.dto.response.WishlistProductResponse;
import vn.teasmart.backend.exception.ResourceNotFoundException;
@Service
@Transactional(readOnly = true)
public class WishlistService {
    private final WishlistRepository wishlists;
    private final UserRepository users;
    private final ProductRepository products;
    public WishlistService(WishlistRepository wishlists, UserRepository users, ProductRepository products) {
        this.wishlists = wishlists; this.users = users; this.products = products;
    }
    public List<WishlistProductResponse> getAll(Long userId) {
        requireCustomer(users.findById(userId).orElseThrow(() -> new BadCredentialsException("Authentication failed.")));
        return map(wishlists.findByUser_UserIdOrderByWishlistIdDesc(userId));
    }
    @Transactional
    public List<WishlistProductResponse> add(Long userId, Long productId) {
        User user = lockCustomer(userId);
        var owned = wishlists.findAllByUser_UserIdOrderByWishlistIdDesc(userId);
        if (owned.stream().noneMatch(w -> w.getProduct().getProductId().equals(productId))) {
            Product product = products.findById(productId).orElseThrow(() -> new ResourceNotFoundException("Product not found."));
            Wishlist row = new Wishlist(); row.setUser(user); row.setProduct(product); row.setCreatedAt(PaymentTime.now());
            wishlists.saveAndFlush(row);
        }
        return map(wishlists.findAllByUser_UserIdOrderByWishlistIdDesc(userId));
    }
    @Transactional
    public List<WishlistProductResponse> remove(Long userId, Long productId) {
        lockCustomer(userId);
        var owned = wishlists.findAllByUser_UserIdOrderByWishlistIdDesc(userId);
        owned.stream().filter(w -> w.getProduct().getProductId().equals(productId)).findFirst().ifPresent(wishlists::delete);
        wishlists.flush();
        return map(wishlists.findAllByUser_UserIdOrderByWishlistIdDesc(userId));
    }
    private User lockCustomer(Long userId) {
        User u = users.findLockedByUserId(userId).orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        requireCustomer(u); return u;
    }
    private void requireCustomer(User u) {
        if (!"ACTIVE".equals(u.getStatus()) || !"CUSTOMER".equals(u.getRole())) throw new BadCredentialsException("Authentication failed.");
    }
    private List<WishlistProductResponse> map(List<Wishlist> rows) {
        return rows.stream().map(w -> {
            Product p = w.getProduct();
            boolean available = "ACTIVE".equals(p.getStatus()) && "ACTIVE".equals(p.getCategory().getStatus())
                    && "ACTIVE".equals(p.getRegion().getStatus()) && "ACTIVE".equals(p.getStore().getStatus()) && p.getStockQuantity() > 0;
            return new WishlistProductResponse(p.getProductId(), p.getName(), p.getSlug(), p.getPrice(), p.getWeightGrams(),
                    p.getImageUrl(), p.getTasteNote(), p.getCategory().getName(), p.getRegion().getRegionId(), p.getRegion().getName(), available);
        }).toList();
    }
}
