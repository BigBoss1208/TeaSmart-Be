package vn.teasmart.backend.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.dto.response.ProductResponse;
import vn.teasmart.backend.dto.response.ProductSummaryResponse;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.repository.ProductRepository;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final String ACTIVE = "ACTIVE";

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public PageResponse<ProductSummaryResponse> listPublicProducts(String keyword, Pageable pageable) {
        // All visibility conditions must be applied before the database paginates.
        Page<Product> products = keyword == null || keyword.isBlank()
                ? productRepository.findByStatusAndCategory_StatusAndRegion_StatusAndStore_Status(
                        ACTIVE, ACTIVE, ACTIVE, ACTIVE, pageable)
                : productRepository.findByStatusAndCategory_StatusAndRegion_StatusAndStore_StatusAndNameContainingIgnoreCase(
                        ACTIVE, ACTIVE, ACTIVE, ACTIVE, keyword.trim(), pageable);

        return new PageResponse<>(
                products.getContent().stream().map(this::toSummary).toList(),
                products.getNumber(),
                products.getSize(),
                products.getTotalElements(),
                products.getTotalPages());
    }

    public ProductResponse getPublicProductBySlug(String slug) {
        Product product = productRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found."));

        if (!ACTIVE.equals(product.getStatus())
                || !ACTIVE.equals(product.getCategory().getStatus())
                || !ACTIVE.equals(product.getRegion().getStatus())
                || !ACTIVE.equals(product.getStore().getStatus())) {
            throw new ResourceNotFoundException("Product not found.");
        }

        return new ProductResponse(
                product.getProductId(), product.getName(), product.getSlug(),
                product.getDescription(), product.getPrice(), product.getStockQuantity(),
                product.getWeightGrams(), product.getImageUrl(), product.getTasteNote(),
                product.getStrengthLevel(), product.getAstringencyLevel(),
                product.getAromaLevel(), product.getAftertasteLevel(),
                product.getCategory().getCategoryId(), product.getCategory().getName(),
                product.getRegion().getRegionId(), product.getRegion().getName(),
                product.getStore().getStoreId(), product.getStore().getName());
    }

    private ProductSummaryResponse toSummary(Product product) {
        return new ProductSummaryResponse(
                product.getProductId(), product.getName(), product.getSlug(),
                product.getPrice(), product.getWeightGrams(), product.getImageUrl(),
                product.getTasteNote(),
                product.getCategory().getCategoryId(), product.getCategory().getName(),
                product.getRegion().getRegionId(), product.getRegion().getName());
    }
}
