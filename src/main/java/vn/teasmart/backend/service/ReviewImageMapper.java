package vn.teasmart.backend.service;

import java.util.*;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.dto.response.ReviewImageResponse;
import vn.teasmart.backend.entity.ReviewImage;
import vn.teasmart.backend.repository.ReviewImageRepository;

@Component
public class ReviewImageMapper {
    private final ReviewImageRepository images;
    public ReviewImageMapper(ReviewImageRepository images) { this.images = images; }
    public Map<Long,List<ReviewImageResponse>> forReviews(Collection<Long> ids) {
        Map<Long,List<ReviewImageResponse>> result = new HashMap<>();
        if (!ids.isEmpty()) for (ReviewImage image : images.findByReview_ReviewIdInOrderByReviewImageIdAsc(ids)) {
            result.computeIfAbsent(image.getReview().getReviewId(), ignored -> new ArrayList<>()).add(toResponse(image));
        }
        return result;
    }
    public List<ReviewImageResponse> forReview(Long id) { return forReviews(List.of(id)).getOrDefault(id,List.of()); }
    public static ReviewImageResponse toResponse(ReviewImage image) {
        return new ReviewImageResponse(image.getReviewImageId(), "/api/review-images/" + image.getReviewImageId(),
                image.getContentType(),image.getFileSize(),image.getCreatedAt());
    }
}
