package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.request.CreateReviewRequest;
import vn.teasmart.backend.dto.request.UpdateReviewRequest;
import vn.teasmart.backend.dto.response.PageResponse;
import vn.teasmart.backend.dto.response.ReviewResponse;
import vn.teasmart.backend.service.ReviewService;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateReviewRequest request) {
        return service.create(Long.valueOf(jwt.getSubject()), request);
    }

    @GetMapping("/my")
    public PageResponse<ReviewResponse> getMine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        return service.getMine(Long.valueOf(jwt.getSubject()),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "reviewId")));
    }

    @PutMapping("/{reviewId}")
    public ReviewResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long reviewId,
            @Valid @RequestBody UpdateReviewRequest request) {
        return service.update(Long.valueOf(jwt.getSubject()), reviewId, request);
    }

    @DeleteMapping("/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long reviewId) {
        service.delete(Long.valueOf(jwt.getSubject()), reviewId);
    }
}
