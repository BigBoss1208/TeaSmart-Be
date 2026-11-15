package vn.teasmart.backend.dto.response;
import java.util.List;
import java.math.BigDecimal;
import vn.teasmart.backend.dto.request.TeaPreferenceRequest;
public record RagChatResponse(String method,String reply,TeaPreferenceRequest preferences,List<String> notices,
    TeaRecommendationResponse recommendations,List<Source> sources,String embeddingMethod,boolean llmUsed,
    String businessKind,List<BusinessProduct> businessProducts) {
    public record Source(String id,String title,String url,String publisher,String accessedAt){}
    public record BusinessProduct(Long productId,String name,String slug,BigDecimal price,Long stockQuantity,
        Long unitsSold,BigDecimal averageRating,Long reviewCount){}
}
