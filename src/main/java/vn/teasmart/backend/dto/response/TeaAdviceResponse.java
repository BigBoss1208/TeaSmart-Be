package vn.teasmart.backend.dto.response;

import java.util.List;
import vn.teasmart.backend.dto.request.TeaPreferenceRequest;

public record TeaAdviceResponse(String method, String reply, TeaPreferenceRequest preferences,
                               List<String> notices, TeaRecommendationResponse recommendations) {}
