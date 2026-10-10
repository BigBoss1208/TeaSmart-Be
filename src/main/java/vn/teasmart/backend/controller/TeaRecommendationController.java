package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import vn.teasmart.backend.service.TeaAdvisorService;
import vn.teasmart.backend.dto.request.TeaPreferenceRequest;
import vn.teasmart.backend.dto.response.TeaRecommendationResponse;
import vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse;

@RestController
@RequestMapping("/api/recommendations")
public class TeaRecommendationController {
    private final TeaAdvisorService advisor;
    public TeaRecommendationController(TeaAdvisorService advisor){this.advisor=advisor;}
    @GetMapping public TeaRecommendationResponse related(@RequestParam(required=false) @Positive Long productId,
            @RequestParam(defaultValue="4") @Min(1) @Max(12) int limit,
            @RequestParam(required=false) @Pattern(regexp="STRONG|LOW_ASTRINGENCY|SWEET|AROMATIC|DAILY|GIFT") String profile) {return advisor.related(productId,limit,profile);}
    @PostMapping("/preferences") public TeaRecommendationResponse preferences(@Valid @RequestBody TeaPreferenceRequest request,
            @RequestParam(defaultValue="4") @Min(1) @Max(12) int limit) {return advisor.preferences(request,limit);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PREFERENCES","Invalid tea preference or price range."));
    }
}
