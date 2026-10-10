package vn.teasmart.backend.service;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.repository.AdminReportingRepository;
import vn.teasmart.backend.dto.response.AdminDashboardResponse;
import vn.teasmart.backend.dto.response.AdminDashboardResponse.RevenueDay;
@Service
@Transactional(readOnly=true)
public class AdminDashboardService {
    private final AdminReportingRepository reporting;
    public AdminDashboardService(AdminReportingRepository reporting) { this.reporting=reporting; }
    public AdminDashboardResponse get(LocalDate from,LocalDate to) {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        LocalDate end=to==null?today:to; LocalDate start=from==null?end.minusDays(29):from;
        if(start.isAfter(end)||ChronoUnit.DAYS.between(start,end)>365||start.getYear()<2000||end.getYear()>9998)
            throw new IllegalArgumentException("Date range must be ordered and at most 366 days.");
        var counts=reporting.orderCounts(start.atStartOfDay(),end.plusDays(1).atStartOfDay());
        Map<LocalDate,RevenueDay> actual=new HashMap<>();
        for(var day:reporting.revenue(start.atStartOfDay(),end.plusDays(1).atStartOfDay())) actual.put(day.date(),day);
        List<RevenueDay> days=new ArrayList<>();
        for(LocalDate d=start;!d.isAfter(end);d=d.plusDays(1)) days.add(actual.getOrDefault(d,new RevenueDay(d,BigDecimal.ZERO,0)));
        return new AdminDashboardResponse(start,end,"Asia/Ho_Chi_Minh",
            days.stream().map(RevenueDay::revenue).reduce(BigDecimal.ZERO,BigDecimal::add),
            days.stream().mapToLong(RevenueDay::paymentsCount).sum(),counts.values().stream().mapToLong(Long::longValue).sum(),
            reporting.count("products"),reporting.count("customers"),counts,days);
    }
}
