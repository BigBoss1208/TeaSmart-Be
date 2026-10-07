package vn.teasmart.backend.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.CategoryResponse;
import vn.teasmart.backend.entity.Category;
import vn.teasmart.backend.repository.CategoryRepository;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private static final String ACTIVE = "ACTIVE";

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<CategoryResponse> listPublicCategories() {
        return repository.findByStatusOrderByNameAsc(ACTIVE).stream()
                .map(this::toResponse).toList();
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getCategoryId(),
                category.getName(),
                category.getSlug(),
                category.getDescription());
    }
}
