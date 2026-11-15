package vn.teasmart.backend.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;
    private final boolean sellerFoundationEnabled;

    public ProductService(ProductRepository productRepository, JdbcTemplate jdbc,
            @Value("${teasmart.seller.foundation-enabled:false}") boolean sellerFoundationEnabled) {
        this.productRepository = productRepository;
        this.jdbc = jdbc;
        this.sellerFoundationEnabled = sellerFoundationEnabled;
    }

    public PageResponse<ProductSummaryResponse> listPublicProducts(String keyword, Pageable pageable) {
        if (sellerFoundationEnabled) return listPublicProductsWithSellers(keyword, pageable);
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
        if (sellerFoundationEnabled) {
            var rows = jdbc.query(publicProductSelect() + " AND p.slug=?", (r, n) -> new ProductResponse(
                    r.getLong("product_id"), r.getString("name"), r.getString("slug"),
                    r.getString("description"), r.getBigDecimal("price"), r.getLong("stock_quantity"),
                    r.getLong("weight_grams"), r.getString("image_url"), r.getString("taste_note"),
                    r.getObject("strength_level", Integer.class), r.getObject("astringency_level", Integer.class),
                    r.getObject("aroma_level", Integer.class), r.getObject("aftertaste_level", Integer.class),
                    r.getLong("category_id"), r.getString("category_name"),
                    r.getLong("region_id"), r.getString("region_name"),
                    r.getLong("store_id"), r.getString("store_name"), r.getObject("shop_id",Long.class), r.getString("shop_name")), slug);
            if (rows.isEmpty()) throw new ResourceNotFoundException("Product not found.");
            return rows.get(0);
        }
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
                product.getStore().getStoreId(), product.getStore().getName(), null, null);
    }

    private ProductSummaryResponse toSummary(Product product) {
        return new ProductSummaryResponse(
                product.getProductId(), product.getName(), product.getSlug(),
                product.getPrice(), product.getWeightGrams(), product.getImageUrl(),
                product.getTasteNote(),
                product.getCategory().getCategoryId(), product.getCategory().getName(),
                product.getRegion().getRegionId(), product.getRegion().getName());
    }

    private PageResponse<ProductSummaryResponse> listPublicProductsWithSellers(String keyword, Pageable pageable) {
        String filter = publicProductFrom() + publicProductWhere() + " AND (? IS NULL OR ?='' OR p.name LIKE CONCAT('%',?,'%'))";
        String normalized = keyword == null ? null : keyword.trim();
        long total = jdbc.queryForObject("SELECT COUNT(*)" + filter, Long.class,
                normalized, normalized, normalized);
        var content = jdbc.query(publicProductSelect() + " AND (? IS NULL OR ?='' OR p.name LIKE CONCAT('%',?,'%')) ORDER BY p.product_id DESC LIMIT ? OFFSET ?",
                (r, n) -> new ProductSummaryResponse(r.getLong("product_id"), r.getString("name"),
                        r.getString("slug"), r.getBigDecimal("price"), r.getLong("weight_grams"),
                        r.getString("image_url"), r.getString("taste_note"), r.getLong("category_id"),
                        r.getString("category_name"), r.getLong("region_id"), r.getString("region_name")),
                normalized, normalized, normalized, pageable.getPageSize(), pageable.getOffset());
        return new PageResponse<>(content, pageable.getPageNumber(), pageable.getPageSize(), total,
                (int) ((total + pageable.getPageSize() - 1) / pageable.getPageSize()));
    }

    private String publicProductSelect() {
        return "SELECT p.product_id,p.name,p.slug,p.description,p.price,p.stock_quantity,p.weight_grams,p.image_url,p.taste_note,p.strength_level,p.astringency_level,p.aroma_level,p.aftertaste_level,p.category_id,c.name category_name,p.region_id,r.name region_name,p.store_id,st.name store_name,p.shop_id,(SELECT sh.name FROM shops sh WHERE sh.shop_id=p.shop_id) shop_name"
                + publicProductFrom() + publicProductWhere();
    }

    private String publicProductFrom() {
        return " FROM products p JOIN categories c ON c.category_id=p.category_id JOIN tea_regions r ON r.region_id=p.region_id JOIN stores st ON st.store_id=p.store_id";
    }

    private String publicProductWhere() {
        return " WHERE c.status='ACTIVE' AND r.status='ACTIVE' AND st.status='ACTIVE' AND ((p.shop_id IS NULL AND p.status='ACTIVE') OR (p.shop_id IS NOT NULL AND p.status='INACTIVE' AND p.seller_listing_status='ACTIVE' AND EXISTS (SELECT 1 FROM shops sh JOIN users u ON u.user_id=sh.owner_user_id JOIN stores linked_store ON linked_store.store_id=sh.catalog_store_id WHERE sh.shop_id=p.shop_id AND sh.status='ACTIVE' AND u.status='ACTIVE' AND u.role='CUSTOMER' AND linked_store.status='ACTIVE' AND sh.catalog_store_id=p.store_id AND sh.catalog_verified_by IS NOT NULL AND sh.catalog_verified_at IS NOT NULL)))";
    }
}
