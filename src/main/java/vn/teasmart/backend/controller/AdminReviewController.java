package vn.teasmart.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.response.AdminReviewResponse;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.enums.ReviewStatus;
import vn.teasmart.backend.service.AdminReviewService;

@RestController
@RequestMapping("/api/admin/reviews")
public class AdminReviewController {
    private final AdminReviewService service;

    public AdminReviewController(AdminReviewService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AdminReviewResponse> getAll(
            @RequestParam(required = false) @Positive Long productId,
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) @Min(1) @Max(5) Integer rating,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return service.getAll(productId, status, rating,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "reviewId")));
    }

    @GetMapping("/{reviewId}")
    public AdminReviewResponse getById(@PathVariable @Positive Long reviewId) {
        return service.getById(reviewId);
    }

    @PatchMapping("/{reviewId}/approve")
    public AdminReviewResponse approve(@PathVariable @Positive Long reviewId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        rejectBody(body, request);
        return service.approve(reviewId);
    }

    @PatchMapping("/{reviewId}/hide")
    public AdminReviewResponse hide(@PathVariable @Positive Long reviewId,
            @RequestBody(required = false) String body, HttpServletRequest request) {
        rejectBody(body, request);
        return service.hide(reviewId);
    }

    private void rejectBody(String body, HttpServletRequest request) {
        if (body != null && !body.isBlank()) {
            throw new HttpMessageNotReadableException("This endpoint does not accept a request body.",
                    new ServletServerHttpRequest(request));
        }
    }
}
