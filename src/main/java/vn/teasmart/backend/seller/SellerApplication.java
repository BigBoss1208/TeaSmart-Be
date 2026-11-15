package vn.teasmart.backend.seller;
import java.time.LocalDateTime;
/** Immutable JDBC entity. Kept outside JPA so disabled onboarding requires no new tables. */
public record SellerApplication(Long applicationId, Long applicantUserId, String shopName,
        String representativeName, String phone, String businessAddress, String description,
        String status, Long reviewerUserId, String rejectionReason, LocalDateTime createdAt,
        LocalDateTime reviewedAt, String businessType, String representativeEmail, String province, String ward, String addressDetail, String applicationLogoUrl, String coverUrl) {}
