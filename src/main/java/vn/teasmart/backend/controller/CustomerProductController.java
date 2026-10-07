package vn.teasmart.backend.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.dto.response.ProductResponse;
import vn.teasmart.backend.dto.response.ProductSummaryResponse;
import vn.teasmart.backend.service.ProductService;

@RestController
@RequestMapping("/api/products")
public class CustomerProductController {

    private final ProductService productService;

    public CustomerProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public PageResponse<ProductSummaryResponse> listProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword) {
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("Invalid pagination.");
        }
        return productService.listPublicProducts(
                keyword, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "productId")));
    }

    @GetMapping("/{slug}")
    public ProductResponse getProduct(@PathVariable String slug) {
        return productService.getPublicProductBySlug(slug);
    }
}
