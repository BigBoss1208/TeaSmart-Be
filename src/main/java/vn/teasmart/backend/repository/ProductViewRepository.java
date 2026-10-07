package vn.teasmart.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.ProductView;

public interface ProductViewRepository extends JpaRepository<ProductView, Long> {
}
