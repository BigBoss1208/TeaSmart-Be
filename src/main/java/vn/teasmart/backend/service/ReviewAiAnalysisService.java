package vn.teasmart.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.ReviewAiAnalysisResponse;
import vn.teasmart.backend.exception.ReviewAiException;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ReviewAiAnalysisService {
    private final ReviewAiAnalysisTransactionService transactions;
    private final ReviewAiClient client;

    public ReviewAiAnalysisService(ReviewAiAnalysisTransactionService transactions, ReviewAiClient client) {
        this.transactions = transactions;
        this.client = client;
    }

    public ReviewAiAnalysisResponse get(Long adminId, Long reviewId) {
        return transactions.get(adminId, reviewId);
    }

    public ReviewAiAnalysisResponse analyze(Long adminId, Long reviewId) {
        var run = transactions.begin(adminId, reviewId);
        ReviewAiClient.Result result = null;
        ReviewAiException failure = null;
        try {
            result = client.analyze(run.comment(), run.rating());
        } catch (ReviewAiException exception) {
            failure = exception;
        }
        var completion = transactions.finish(adminId, run, result, failure);
        if (completion.error() != null) throw completion.error();
        return completion.response();
    }
}
