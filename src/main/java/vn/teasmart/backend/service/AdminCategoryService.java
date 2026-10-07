package vn.teasmart.backend.service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AdminCategoryRequest;
import vn.teasmart.backend.dto.request.AdminCategoryStatusRequest;
import vn.teasmart.backend.dto.response.AdminCategoryResponse;
import vn.teasmart.backend.entity.Category;
import vn.teasmart.backend.exception.CategoryConflictException;
import vn.teasmart.backend.exception.InvalidCategoryNameException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.CategoryRepository;

@Service
@Transactional(readOnly = true)
public class AdminCategoryService {
    private final CategoryRepository repository;

    public AdminCategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<AdminCategoryResponse> getAll() {
        return repository.findAll(Sort.by("name").ascending().and(Sort.by("categoryId").ascending()))
                .stream().map(this::toResponse).toList();
    }

    public AdminCategoryResponse getById(Long categoryId) {
        return toResponse(requireCategory(categoryId));
    }

    @Transactional
    public AdminCategoryResponse create(AdminCategoryRequest request) {
        if (repository.existsByNameIgnoreCase(request.name())) {
            throw new CategoryConflictException("Category name already exists.");
        }
        String slug = slugFromName(request.name());
        if (repository.existsBySlug(slug)) {
            throw new CategoryConflictException("Category slug already exists. Choose another name.");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Category category = new Category();
        category.setName(request.name());
        category.setSlug(slug);
        category.setDescription(request.description());
        category.setStatus(request.status());
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        return save(category);
    }

    @Transactional
    public AdminCategoryResponse update(Long categoryId, AdminCategoryRequest request) {
        Category category = requireCategory(categoryId);
        if (repository.existsByNameIgnoreCaseAndCategoryIdNot(request.name(), categoryId)) {
            throw new CategoryConflictException("Category name already exists.");
        }
        category.setName(request.name());
        category.setDescription(request.description());
        category.setStatus(request.status());
        category.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(category);
    }

    @Transactional
    public AdminCategoryResponse updateStatus(Long categoryId, AdminCategoryStatusRequest request) {
        Category category = requireCategory(categoryId);
        category.setStatus(request.status());
        category.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(category);
    }

    private Category requireCategory(Long categoryId) {
        return repository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found."));
    }

    private String slugFromName(String name) {
        String slug = Normalizer.normalize(name.toLowerCase(Locale.ROOT)
                        .replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new InvalidCategoryNameException();
        }
        return slug;
    }

    private AdminCategoryResponse save(Category category) {
        try {
            repository.saveAndFlush(category);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getErrorCode() == 1062 && violation.getConstraintName() != null) {
                    String constraint = violation.getConstraintName().replace("`", "").replace("'", "");
                    if (constraint.equals("uk_categories_name") || constraint.equals("categories.uk_categories_name")) {
                        throw new CategoryConflictException("Category name already exists.");
                    }
                    if (constraint.equals("uk_categories_slug") || constraint.equals("categories.uk_categories_slug")) {
                        throw new CategoryConflictException("Category slug already exists. Choose another name.");
                    }
                }
            }
            throw exception;
        }
        return toResponse(category);
    }

    private AdminCategoryResponse toResponse(Category category) {
        return new AdminCategoryResponse(category.getCategoryId(), category.getName(), category.getSlug(),
                category.getDescription(), category.getStatus(), category.getCreatedAt(), category.getUpdatedAt());
    }
}
