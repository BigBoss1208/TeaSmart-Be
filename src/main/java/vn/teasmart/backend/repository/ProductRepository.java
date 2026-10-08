package vn.teasmart.backend.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Product> findLockedByProductId(Long productId);

    boolean existsBySlug(String slug);

    Page<Product> findByNameContainingIgnoreCase(String keyword, Pageable pageable);

    Optional<Product> findBySlug(String slug);

    Page<Product> findByStatus(String status, Pageable pageable);

    Page<Product> findByStatusAndNameContainingIgnoreCase(String status, String keyword, Pageable pageable);

    Page<Product> findByStatusAndCategory_StatusAndRegion_StatusAndStore_Status(
            String status, String categoryStatus, String regionStatus, String storeStatus, Pageable pageable);

    Page<Product> findByStatusAndCategory_StatusAndRegion_StatusAndStore_StatusAndNameContainingIgnoreCase(
            String status, String categoryStatus, String regionStatus, String storeStatus,
            String keyword, Pageable pageable);
}
