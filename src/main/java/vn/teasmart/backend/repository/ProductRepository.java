package vn.teasmart.backend.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySlug(String slug);

    Page<Product> findByStatus(String status, Pageable pageable);

    Page<Product> findByStatusAndNameContainingIgnoreCase(String status, String keyword, Pageable pageable);
}
