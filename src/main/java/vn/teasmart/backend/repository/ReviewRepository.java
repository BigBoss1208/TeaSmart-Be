package vn.teasmart.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByOrderItem_OrderItemId(Long orderItemId);

    Page<Review> findByProduct_ProductIdAndStatus(Long productId, String status, Pageable pageable);
}
