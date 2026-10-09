package vn.teasmart.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.teasmart.backend.storage.ReviewImageStorage;

@Configuration
@EnableScheduling
public class ReviewImageCleanupConfig {
    private final ReviewImageStorage storage;
    public ReviewImageCleanupConfig(ReviewImageStorage storage) { this.storage=storage; }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void retryConfirmedDeletes() { storage.retryDeletes(); }
}
