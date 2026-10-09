package vn.teasmart.backend.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import vn.teasmart.backend.enums.AiAnalysisMethod;
import vn.teasmart.backend.enums.SentimentLabel;

public record ReviewAiAnalysisResponse(
        SentimentLabel sentimentLabel, BigDecimal confidence, Boolean needsReview,
        List<String> moderationFlags, List<String> reasons, AiAnalysisMethod analysisMethod,
        String modelVersion, String processingStatus, LocalDateTime analyzedAt,
        boolean isStale, String errorCode) {
}
