package vn.teasmart.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.dto.response.ReviewAiAnalysisResponse;
import vn.teasmart.backend.service.ReviewAiAnalysisService;

@RestController
@RequestMapping("/api/admin/reviews/{reviewId}/ai-analysis")
public class AdminReviewAiController {
    private final ReviewAiAnalysisService service;
    public AdminReviewAiController(ReviewAiAnalysisService service) { this.service = service; }

    @GetMapping
    public ReviewAiAnalysisResponse get(@PathVariable @Positive Long reviewId, @AuthenticationPrincipal Jwt jwt) {
        return service.get(Long.valueOf(jwt.getSubject()), reviewId);
    }

    @PostMapping
    public ReviewAiAnalysisResponse analyze(@PathVariable @Positive Long reviewId,
            @AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) String body,
            HttpServletRequest request) {
        if (body != null && !body.isBlank()) {
            throw new HttpMessageNotReadableException("This endpoint does not accept a request body.",
                    new ServletServerHttpRequest(request));
        }
        return service.analyze(Long.valueOf(jwt.getSubject()), reviewId);
    }
}
