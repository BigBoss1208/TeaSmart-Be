package vn.teasmart.backend.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Cart;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUser_UserId(Long userId);
}
