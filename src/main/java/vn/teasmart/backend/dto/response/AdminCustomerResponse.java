package vn.teasmart.backend.dto.response;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record AdminCustomerResponse(Long userId, String fullName, String email, String phone,
        String status, LocalDateTime createdAt, LocalDateTime updatedAt, long ordersCount, BigDecimal totalSpent) {}
