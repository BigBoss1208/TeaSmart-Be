package vn.teasmart.backend.service;

import java.io.InputStream;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.dto.response.LeafPredictionResponse;
import vn.teasmart.backend.exception.LeafAnalysisException;

@Component
public class LeafAnalysisClient {
    private static final int RESPONSE_LIMIT = 32 * 1024;

    private final URI predictUri;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public LeafAnalysisClient(@Value("${teasmart.leaf-ai.base-url}") String baseUrl,
            @Value("${teasmart.leaf-ai.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${teasmart.leaf-ai.request-timeout-ms}") long requestTimeoutMs) {
        URI base = URI.create(baseUrl);
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || connectTimeoutMs <= 0 || requestTimeoutMs <= 0) {
            throw new IllegalArgumentException("Invalid Leaf AI URL or timeout configuration.");
        }
        this.predictUri = base.resolve("/predict");
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .proxy(ProxySelector.of(null))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public LeafPredictionResponse predict(String contentType, InputStream upload, long size) {
        // The controller owns the spool stream. A retry may not replay this one-shot body.
        AtomicBoolean published = new AtomicBoolean();
        var stream = HttpRequest.BodyPublishers.ofInputStream(
                () -> published.compareAndSet(false, true) ? upload : null);
        HttpRequest request = HttpRequest.newBuilder(predictUri)
                .timeout(requestTimeout)
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.fromPublisher(stream, size))
                .build();

        var pending = http.sendAsync(request, ignored -> new BoundedResponse());
        HttpResponse<byte[]> response;
        try {
            // Also bound time spent reading the response body after headers arrive.
            response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw timeout();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw timeout();
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof UnresolvedAddressException) {
                throw unavailable();
            }
            throw LeafAnalysisException.invalidResponse();
        }
        return decode(response, contentType.equals("video/mp4"));
    }

    private LeafPredictionResponse decode(HttpResponse<byte[]> response, boolean videoRequest) {
        try {
            String mime = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
            if (!mime.equalsIgnoreCase("application/json")) {
                throw LeafAnalysisException.invalidResponse();
            }
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw LeafAnalysisException.invalidResponse();
            }
            if (response.statusCode() == 200) {
                return LeafPredictionResponse.parse(body, videoRequest);
            }
            throw upstreamFailure(response.statusCode(), body);
        } catch (LeafAnalysisException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw LeafAnalysisException.invalidResponse();
        }
    }

    private static LeafAnalysisException upstreamFailure(int status, JsonNode body) {
        if (!body.path("code").isString() || !body.path("message").isString()) {
            return LeafAnalysisException.invalidResponse();
        }
        String code = body.path("code").stringValue();
        HttpStatus expected;
        String message;
        switch (code) {
            case "EMPTY_UPLOAD", "INVALID_CONTENT_LENGTH", "UPLOAD_INTERRUPTED" -> {
                expected = HttpStatus.BAD_REQUEST;
                message = "Tệp tải lên rỗng hoặc không đầy đủ.";
            }
            case "MEDIA_TOO_LARGE", "MEDIA_DIMENSIONS_EXCEEDED" -> {
                expected = HttpStatus.PAYLOAD_TOO_LARGE;
                message = "Tệp vượt giới hạn dung lượng hoặc kích thước khung hình.";
            }
            case "UNSUPPORTED_MEDIA_TYPE", "MEDIA_TYPE_MISMATCH", "ANIMATED_IMAGE_UNSUPPORTED" -> {
                expected = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
                message = "Nội dung tệp không đúng định dạng JPG, PNG hoặc MP4 được hỗ trợ.";
            }
            case "VIDEO_TOO_LONG" -> {
                expected = HttpStatus.UNPROCESSABLE_ENTITY;
                message = "Video chỉ được dài tối đa 15 giây.";
            }
            case "INVALID_IMAGE", "INVALID_VIDEO", "INVALID_VIDEO_DURATION", "NO_VIDEO_FRAMES", "VIDEO_DECODE_LIMIT" -> {
                expected = HttpStatus.UNPROCESSABLE_ENTITY;
                message = "Không thể giải mã ảnh hoặc video trong giới hạn phân tích.";
            }
            case "LEAF_BUSY" -> {
                expected = HttpStatus.TOO_MANY_REQUESTS;
                message = "Dịch vụ đang xử lý tệp khác. Vui lòng thử lại.";
            }
            case "MODEL_NOT_READY", "INFERENCE_UNAVAILABLE", "TEMPORARY_UPLOAD_UNAVAILABLE" -> {
                expected = HttpStatus.SERVICE_UNAVAILABLE;
                message = "Dịch vụ phân loại lá chè chưa sẵn sàng.";
            }
            case "UPLOAD_TIMEOUT" -> {
                return status == 408 ? timeout() : LeafAnalysisException.invalidResponse();
            }
            default -> {
                return LeafAnalysisException.invalidResponse();
            }
        }
        return status == expected.value()
                ? new LeafAnalysisException(expected, code, message) : LeafAnalysisException.invalidResponse();
    }

    private static LeafAnalysisException timeout() {
        return new LeafAnalysisException(HttpStatus.GATEWAY_TIMEOUT, "LEAF_UPSTREAM_TIMEOUT",
                "Dịch vụ phân loại lá chè phản hồi quá thời gian chờ.");
    }

    private static LeafAnalysisException unavailable() {
        return new LeafAnalysisException(HttpStatus.SERVICE_UNAVAILABLE, "LEAF_SERVICE_UNAVAILABLE",
                "Tạm thời không thể kết nối dịch vụ phân loại lá chè.");
    }

    private static final class ResponseTooLarge extends RuntimeException {
    }

    private static final class BoundedResponse implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            bytes += chunks.stream().mapToLong(ByteBuffer::remaining).sum();
            if (bytes > RESPONSE_LIMIT) {
                subscription.cancel();
                delegate.onError(new ResponseTooLarge());
                return;
            }
            delegate.onNext(chunks);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
