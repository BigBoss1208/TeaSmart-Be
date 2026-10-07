package vn.teasmart.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
