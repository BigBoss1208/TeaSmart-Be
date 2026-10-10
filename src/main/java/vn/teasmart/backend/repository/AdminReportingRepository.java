package vn.teasmart.backend.repository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.teasmart.backend.dto.response.AdminDashboardResponse.RevenueDay;
@Repository
public class AdminReportingRepository {
    private final JdbcTemplate jdbc;
    public AdminReportingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public long count(String kind) {
        String sql=switch(kind) { case "products" -> "SELECT COUNT(*) FROM products";
            case "customers" -> "SELECT COUNT(*) FROM users WHERE role='CUSTOMER'";
            default -> throw new IllegalArgumentException("Unsupported count."); };
        return jdbc.queryForObject(sql,Long.class);
    }
    public Map<String,Long> orderCounts(LocalDateTime from, LocalDateTime until) {
        Map<String,Long> counts=new LinkedHashMap<>();
        for(String status:List.of("PENDING","CONFIRMED","SHIPPING","DELIVERED","CANCELLED")) counts.put(status,0L);
        jdbc.query("SELECT order_status,COUNT(*) n FROM orders WHERE created_at>=? AND created_at<? GROUP BY order_status ORDER BY order_status",
            rs->{counts.put(rs.getString("order_status"),rs.getLong("n"));},from,until);
        return counts;
    }
    public List<RevenueDay> revenue(LocalDateTime from, LocalDateTime until) {
        // Payments alone: joining OrderItems here would multiply revenue. DATETIME paid_at stores Vietnam local time.
        return jdbc.query("SELECT DATE(paid_at) d,COALESCE(SUM(amount),0) total,COUNT(*) n FROM payments WHERE payment_status='PAID' AND paid_at>=? AND paid_at<? GROUP BY DATE(paid_at) ORDER BY d",
            (rs,i)->new RevenueDay(rs.getDate("d").toLocalDate(),rs.getBigDecimal("total"),rs.getLong("n")),from,until);
    }
    public Map<Long,CustomerTotals> customerTotals(List<Long> ids) {
        Map<Long,CustomerTotals> totals=new HashMap<>(); if(ids.isEmpty()) return totals;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        jdbc.query("SELECT o.user_id,COUNT(o.order_id) n,COALESCE(SUM(CASE WHEN p.payment_status='PAID' AND p.paid_at IS NOT NULL THEN p.amount ELSE 0 END),0) total FROM orders o LEFT JOIN payments p ON p.order_id=o.order_id WHERE o.user_id IN ("+placeholders+") GROUP BY o.user_id",
            rs->{totals.put(rs.getLong("user_id"),new CustomerTotals(rs.getLong("n"),rs.getBigDecimal("total")));},ids.toArray());
        return totals;
    }
    public record CustomerTotals(long ordersCount,BigDecimal totalSpent) {}
}
