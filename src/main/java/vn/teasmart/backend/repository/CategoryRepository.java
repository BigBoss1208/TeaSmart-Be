package vn.teasmart.backend.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByStatusOrderByNameAsc(String status);

    boolean existsByNameIgnoreCase(String name);

    boolean existsBySlug(String slug);

    boolean existsByNameIgnoreCaseAndCategoryIdNot(String name, Long categoryId);
}
