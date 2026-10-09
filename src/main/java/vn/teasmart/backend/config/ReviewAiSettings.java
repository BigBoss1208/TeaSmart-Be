package vn.teasmart.backend.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReviewAiSettings {
    private final URI analyzeUri;
    private final int timeoutSeconds;
    private final int leaseSeconds;

    public ReviewAiSettings(
            @Value("${teasmart.review-ai.base-url}") String baseUrl,
            @Value("${teasmart.review-ai.timeout-seconds}") int timeoutSeconds,
            @Value("${teasmart.review-ai.lease-seconds}") int leaseSeconds) {
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalStateException("Invalid Review AI base URL configuration.");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 60
                || leaseSeconds < timeoutSeconds + 10 || leaseSeconds > 300) {
            throw new IllegalStateException("Invalid Review AI timeout/lease configuration.");
        }
        this.analyzeUri = uri.resolve("/analyze");
        this.timeoutSeconds = timeoutSeconds;
        this.leaseSeconds = leaseSeconds;
    }

    public URI analyzeUri() { return analyzeUri; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public int leaseSeconds() { return leaseSeconds; }
}
