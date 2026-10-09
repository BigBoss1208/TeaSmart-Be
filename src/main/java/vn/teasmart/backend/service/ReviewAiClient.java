package vn.teasmart.backend.service;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.config.ReviewAiSettings;
import vn.teasmart.backend.enums.*;
import vn.teasmart.backend.exception.ReviewAiException;

@Component
public class ReviewAiClient {
    private static final Set<String> FIELDS = Set.of("sentimentLabel", "confidence", "needsReview",
            "moderationFlags", "reasons", "analysisMethod", "modelVersion", "processingStatus", "errorCode");
    public static final Set<String> FLAGS = Set.of("ADVERTISING_LINK", "ADVERTISING_LANGUAGE",
            "REPETITIVE_CONTENT", "POSSIBLE_INSULT", "RATING_SENTIMENT_MISMATCH");
    private final ReviewAiSettings settings;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public ReviewAiClient(ReviewAiSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Result(SentimentLabel sentiment, Boolean needsReview, List<String> flags,
            AiAnalysisMethod method, String version, AiProcessingStatus status, String errorCode) { }

    public Result analyze(String comment, Integer rating) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AI HTTP calls must execute outside database transactions.");
        }
        var body = json.createObjectNode();
        if (comment == null) body.putNull("comment"); else body.put("comment", comment);
        body.put("rating", rating);
        var request = HttpRequest.newBuilder(settings.analyzeUri())
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> new LimitedBody());
        try {
            HttpResponse<byte[]> response = future.get(settings.timeoutSeconds(), TimeUnit.SECONDS);
            int status = response.statusCode();
            if (status == 504 || status == 408) throw failure(HttpStatus.GATEWAY_TIMEOUT, "REVIEW_AI_TIMEOUT");
            if (status == 503) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "REVIEW_AI_UNAVAILABLE");
            if (status != 200) throw invalid();
            String type = response.headers().firstValue("content-type").orElse("").split(";")[0].trim();
            if (!type.equalsIgnoreCase("application/json")) throw invalid();
            return validate(json.readTree(response.body()));
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw failure(HttpStatus.GATEWAY_TIMEOUT, "REVIEW_AI_TIMEOUT");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "REVIEW_AI_UNAVAILABLE");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HttpTimeoutException) throw failure(HttpStatus.GATEWAY_TIMEOUT, "REVIEW_AI_TIMEOUT");
            if (cause instanceof InvalidBody) throw invalid();
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "REVIEW_AI_UNAVAILABLE");
        } catch (ReviewAiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private Result validate(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != FIELDS.size()) throw invalid();
        for (String field : FIELDS) if (!node.has(field)) throw invalid();
        if (!node.get("confidence").isNull()) throw invalid();
        String version = text(node.get("modelVersion"), 200);
        if (!version.matches("[A-Za-z0-9@;._=:+-]{1,200}")) throw invalid();
        String methodText = text(node.get("analysisMethod"), 20);
        if (!(methodText.equals("MODEL") || methodText.equals("RULE_BASED"))) throw invalid();
        AiAnalysisMethod method = AiAnalysisMethod.valueOf(methodText);
        String status = text(node.get("processingStatus"), 20);
        List<String> flags = strings(node.get("moderationFlags"), 5, 50);
        if (new HashSet<>(flags).size() != flags.size() || !FLAGS.containsAll(flags)) throw invalid();
        List<String> reasons = strings(node.get("reasons"), 5, 250);
        if (reasons.size() != flags.size()) throw invalid();
        JsonNode label = node.get("sentimentLabel"), needs = node.get("needsReview"), error = node.get("errorCode");
        if (status.equals("COMPLETED")) {
            if (method != AiAnalysisMethod.MODEL || !label.isString() || !needs.isBoolean()
                    || !error.isNull() || needs.booleanValue() != !flags.isEmpty()) throw invalid();
            SentimentLabel sentiment;
            try { sentiment = SentimentLabel.valueOf(label.stringValue()); }
            catch (IllegalArgumentException exception) { throw invalid(); }
            return new Result(sentiment, needs.booleanValue(), List.copyOf(flags), method, version,
                    AiProcessingStatus.COMPLETED, null);
        }
        if (!status.equals("SKIPPED") || !label.isNull() || !needs.isNull() || !flags.isEmpty()
                || !reasons.isEmpty() || !(error.isNull() || (error.isString() && error.stringValue().equals("INPUT_TOO_LONG")))) {
            throw invalid();
        }
        if (!error.isNull() && method != AiAnalysisMethod.MODEL) throw invalid();
        return new Result(null, null, List.of(), method, version, AiProcessingStatus.SKIPPED,
                error.isNull() ? null : error.stringValue());
    }

    private String text(JsonNode node, int length) {
        if (!node.isString() || node.stringValue().isBlank() || node.stringValue().length() > length) throw invalid();
        return node.stringValue();
    }

    private List<String> strings(JsonNode node, int count, int length) {
        if (!node.isArray() || node.size() > count) throw invalid();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(text(item, length));
        return values;
    }

    private ReviewAiException invalid() { return failure(HttpStatus.BAD_GATEWAY, "REVIEW_AI_INVALID_RESPONSE"); }
    private ReviewAiException failure(HttpStatus status, String code) {
        return new ReviewAiException(status, code, "Review AI service could not provide a valid result.");
    }

    private static class InvalidBody extends RuntimeException { }
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (buffer.size() + chunk.remaining() > 16384) {
                    subscription.cancel();
                    result.completeExceptionally(new InvalidBody());
                    return;
                }
                byte[] bytes = new byte[chunk.remaining()];
                chunk.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
        public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
