package vn.teasmart.backend.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Wishlist;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    Page<Wishlist> findByUser_UserId(Long userId, Pageable pageable);

    boolean existsByUser_UserIdAndProduct_ProductId(Long userId, Long productId);

    Optional<Wishlist> findByUser_UserIdAndProduct_ProductId(Long userId, Long productId);
}
