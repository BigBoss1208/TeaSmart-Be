package vn.teasmart.backend.dto.response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
public record AdminDashboardResponse(LocalDate from, LocalDate to, String timezone,
        BigDecimal revenue, long paidPayments, long ordersCount, long productsCount,
        long customersCount, Map<String,Long> ordersByStatus, List<RevenueDay> revenueByDay) {
    public record RevenueDay(LocalDate date, BigDecimal revenue, long paymentsCount) {}
}
