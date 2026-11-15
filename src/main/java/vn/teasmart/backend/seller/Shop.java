package vn.teasmart.backend.seller;
import java.time.LocalDateTime;
/** JDBC aggregate: ownership and approval identity are immutable. */
public record Shop(Long shopId, Long ownerUserId, Long approvedApplicationId, String name,
        String representativeName, String phone, String businessAddress, String description,
        String logoUrl, String status, String statusReason, Long statusChangedBy,
        LocalDateTime createdAt, LocalDateTime updatedAt, String businessType, String representativeEmail, String province, String ward, String addressDetail, String applicationLogoUrl, String coverUrl) {}
