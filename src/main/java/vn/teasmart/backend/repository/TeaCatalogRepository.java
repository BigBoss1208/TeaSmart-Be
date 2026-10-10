package vn.teasmart.backend.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vn.teasmart.backend.entity.Product;

public interface TeaCatalogRepository extends JpaRepository<Product,Long> {
    // One consistent read of public product fields and relationships; no per-product LAZY queries.
    @Query("SELECT p FROM Product p JOIN FETCH p.category c JOIN FETCH p.region r JOIN FETCH p.store s "
         + "WHERE p.status='ACTIVE' AND c.status='ACTIVE' AND r.status='ACTIVE' AND s.status='ACTIVE' "
         + "ORDER BY p.productId DESC")
    List<Product> findVisibleCatalog();
}
