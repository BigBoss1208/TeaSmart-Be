package vn.teasmart.backend.controller;

import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.dto.response.PublicReviewResponse;
import vn.teasmart.backend.dto.response.ReviewSummaryResponse;
import vn.teasmart.backend.service.ReviewService;

@RestController
@RequestMapping("/api/products/{productId}/reviews")
public class PublicReviewController {
    private final ReviewService service;

    public PublicReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PublicReviewResponse> getPublic(@PathVariable @Positive Long productId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return service.getPublic(productId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "reviewId")));
    }

    @GetMapping("/summary")
    public ReviewSummaryResponse summarize(@PathVariable @Positive Long productId) {
        return service.summarize(productId);
    }
}
