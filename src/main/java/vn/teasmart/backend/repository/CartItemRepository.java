package vn.teasmart.backend.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.teasmart.backend.entity.CartItem;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByCartItemIdAndCart_User_UserId(Long cartItemId, Long userId);

    List<CartItem> findByCart_CartId(Long cartId);

    Optional<CartItem> findByCart_CartIdAndProduct_ProductId(Long cartId, Long productId);

    @Modifying
    @Query("delete from CartItem c where c.cartItemId = :itemId and c.cart.user.userId = :userId and c.product.productId = :productId and c.quantity = :quantity")
    int deleteSnapshotMatch(@Param("itemId") Long itemId, @Param("userId") Long userId,
            @Param("productId") Long productId, @Param("quantity") Long quantity);
}
