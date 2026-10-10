package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record TeaRecommendationResponse(String method, boolean fallback, List<Item> items) {
    public record Item(ProductSummaryResponse product, Long stockQuantity, BigDecimal score,
                       List<String> reasons) {}
}
