package vn.teasmart.backend.service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AdminProductRequest;
import vn.teasmart.backend.dto.request.AdminProductStatusRequest;
import vn.teasmart.backend.dto.response.AdminProductResponse;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.exception.InvalidProductNameException;
import vn.teasmart.backend.exception.ProductConflictException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.CategoryRepository;
import vn.teasmart.backend.repository.ProductRepository;
import vn.teasmart.backend.repository.StoreRepository;
import vn.teasmart.backend.repository.TeaRegionRepository;

@Service
@Transactional(readOnly = true)
public class AdminProductService {
    private final ProductRepository repository;
    private final CategoryRepository categoryRepository;
    private final TeaRegionRepository regionRepository;
    private final StoreRepository storeRepository;

    public AdminProductService(ProductRepository repository, CategoryRepository categoryRepository,
            TeaRegionRepository regionRepository, StoreRepository storeRepository) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.regionRepository = regionRepository;
        this.storeRepository = storeRepository;
    }

    public PageResponse<AdminProductResponse> listProducts(String keyword, Pageable pageable) {
        Page<Product> products = keyword == null || keyword.isBlank()
                ? repository.findAll(pageable)
                : repository.findByNameContainingIgnoreCase(keyword.trim(), pageable);
        return new PageResponse<>(products.getContent().stream().map(this::toResponse).toList(),
                products.getNumber(), products.getSize(), products.getTotalElements(), products.getTotalPages());
    }

    public AdminProductResponse getById(Long productId) {
        return toResponse(requireProduct(productId));
    }

    @Transactional
    public AdminProductResponse create(AdminProductRequest request) {
        String slug = slugFromName(request.name());
        if (repository.existsBySlug(slug)) {
            throw new ProductConflictException();
        }
        Product product = new Product();
        applyRequest(product, request);
        product.setSlug(slug);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        return save(product);
    }

    @Transactional
    public AdminProductResponse update(Long productId, AdminProductRequest request) {
        Product product = requireLockedProduct(productId);
        applyRequest(product, request);
        product.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(product);
    }

    @Transactional
    public AdminProductResponse updateStatus(Long productId, AdminProductStatusRequest request) {
        Product product = requireLockedProduct(productId);
        product.setStatus(request.status());
        product.setUpdatedAt(LocalDateTime.now().withNano(0));
        return save(product);
    }

    private Product requireLockedProduct(Long productId) {
        return repository.findLockedByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
    }

    private Product requireProduct(Long productId) {
        return repository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found."));
    }

    private void applyRequest(Product product, AdminProductRequest request) {
        // Admin may use inactive references; public visibility is enforced by ProductService.
        product.setCategory(categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found.")));
        product.setRegion(regionRepository.findById(request.regionId())
                .orElseThrow(() -> new ResourceNotFoundException("TeaRegion not found.")));
        product.setStore(storeRepository.findById(request.storeId())
                .orElseThrow(() -> new ResourceNotFoundException("Store not found.")));
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQuantity(request.stockQuantity());
        product.setWeightGrams(request.weightGrams());
        product.setImageUrl(request.imageUrl());
        product.setTasteNote(request.tasteNote());
        product.setStrengthLevel(request.strengthLevel());
        product.setAstringencyLevel(request.astringencyLevel());
        product.setAromaLevel(request.aromaLevel());
        product.setAftertasteLevel(request.aftertasteLevel());
        product.setStatus(request.status());
    }

    private String slugFromName(String name) {
        String slug = Normalizer.normalize(name.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty() || slug.length() > 250) {
            throw new InvalidProductNameException();
        }
        return slug;
    }

    private AdminProductResponse save(Product product) {
        try {
            repository.saveAndFlush(product);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getErrorCode() == 1062 && violation.getConstraintName() != null) {
                    String constraint = violation.getConstraintName().replace("`", "").replace("'", "");
                    if (constraint.equals("uk_products_slug") || constraint.equals("products.uk_products_slug")) {
                        throw new ProductConflictException();
                    }
                }
            }
            throw exception;
        }
        return toResponse(product);
    }

    private AdminProductResponse toResponse(Product product) {
        return new AdminProductResponse(product.getProductId(), product.getName(), product.getSlug(),
                product.getDescription(), product.getPrice(), product.getStockQuantity(), product.getWeightGrams(),
                product.getImageUrl(), product.getTasteNote(), product.getStrengthLevel(), product.getAstringencyLevel(),
                product.getAromaLevel(), product.getAftertasteLevel(), product.getStatus(),
                product.getCreatedAt(), product.getUpdatedAt(),
                product.getCategory().getCategoryId(), product.getCategory().getName(), product.getCategory().getStatus(),
                product.getRegion().getRegionId(), product.getRegion().getName(), product.getRegion().getStatus(),
                product.getStore().getStoreId(), product.getStore().getName(), product.getStore().getStatus());
    }
}
