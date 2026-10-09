package vn.teasmart.backend.controller;

import java.util.List;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.teasmart.backend.dto.response.ReviewImageResponse;
import vn.teasmart.backend.service.ReviewImageService;

@RestController
public class ReviewImageController {
    private final ReviewImageService service;
    public ReviewImageController(ReviewImageService service) { this.service=service; }
    @PostMapping(value="/api/reviews/{reviewId}/images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<ReviewImageResponse>> upload(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long reviewId,@RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(Long.valueOf(jwt.getSubject()),reviewId,files));
    }
    @DeleteMapping("/api/reviews/{reviewId}/images/{imageId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable @Positive Long reviewId,
            @PathVariable @Positive Long imageId) {
        service.delete(Long.valueOf(jwt.getSubject()),reviewId,imageId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/review-images/{imageId}")
    public ResponseEntity<byte[]> get(@PathVariable @Positive Long imageId,@AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {
        Long userId=jwt==null?null:Long.valueOf(jwt.getSubject());
        boolean admin=authentication!=null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        var image=service.get(imageId,userId,admin);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .contentLength(image.bytes().length).body(image.bytes());
    }
}
