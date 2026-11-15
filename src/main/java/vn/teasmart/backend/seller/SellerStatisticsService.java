package vn.teasmart.backend.seller;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.response.AdminDashboardResponse.RevenueDay;
@Service
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
@Transactional(readOnly=true)
public class SellerStatisticsService {
 private final JdbcTemplate jdbc;private final SellerService sellers;
 public SellerStatisticsService(JdbcTemplate jdbc,SellerService sellers){this.jdbc=jdbc;this.sellers=sellers;}
 public record TopProduct(long productId,String name,long quantity,BigDecimal orderValue){}
 public record Report(LocalDate from,LocalDate to,String timezone,BigDecimal revenue,long paidPayments,long ordersCount,long productsCount,long customersCount,Map<String,Long> ordersByStatus,List<RevenueDay> revenueByDay,long shopId,String shopName,BigDecimal totalOrderValue,BigDecimal unpaidOrderValue,long itemsSold,List<TopProduct> topProducts){}
 private record Totals(BigDecimal total,BigDecimal unpaid,long buyers){}
 public Report get(long user,LocalDate from,LocalDate to){
  Shop shop=sellers.myShop(user);long id=shop.shopId();
  LocalDate end=to==null?LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")):to,start=from==null?end.minusDays(29):from;
  if(start.isAfter(end)||ChronoUnit.DAYS.between(start,end)>365||start.getYear()<2000||end.getYear()>9998)throw new IllegalArgumentException("Date range must be ordered and at most 366 days.");
  var begin=start.atStartOfDay();var until=end.plusDays(1).atStartOfDay();
  Map<String,Long> counts=new LinkedHashMap<>();for(String status:List.of("PENDING","CONFIRMED","SHIPPING","DELIVERED","CANCELLED"))counts.put(status,0L);
  jdbc.query("SELECT order_status,COUNT(*) n FROM orders WHERE shop_id=? AND created_at>=? AND created_at<? GROUP BY order_status",rs->{counts.put(rs.getString(1),rs.getLong(2));},id,begin,until);
  var totals=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN o.order_status<>'CANCELLED' THEN o.total_amount ELSE 0 END),0),COALESCE(SUM(CASE WHEN o.order_status<>'CANCELLED' AND COALESCE(p.payment_status,'PENDING')<>'PAID' THEN o.total_amount ELSE 0 END),0),COUNT(DISTINCT o.user_id) FROM orders o LEFT JOIN payments p ON p.order_id=o.order_id WHERE o.shop_id=? AND o.created_at>=? AND o.created_at<?",(rs,n)->new Totals(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getLong(3)),id,begin,until);
  var actual=new HashMap<LocalDate,RevenueDay>();
  jdbc.query("SELECT DATE(p.paid_at) d,SUM(p.amount) revenue,COUNT(*) n FROM payments p JOIN orders o ON o.order_id=p.order_id WHERE o.shop_id=? AND p.payment_status='PAID' AND p.paid_at>=? AND p.paid_at<? GROUP BY DATE(p.paid_at)",rs->{var d=rs.getDate(1).toLocalDate();actual.put(d,new RevenueDay(d,rs.getBigDecimal(2),rs.getLong(3)));},id,begin,until);
  var days=new ArrayList<RevenueDay>();for(var d=start;!d.isAfter(end);d=d.plusDays(1))days.add(actual.getOrDefault(d,new RevenueDay(d,BigDecimal.ZERO,0)));
  String sold=" FROM order_items i JOIN orders o ON o.order_id=i.order_id WHERE o.shop_id=? AND o.order_status='DELIVERED' AND o.created_at>=? AND o.created_at<?";
  long items=jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0)"+sold,Long.class,id,begin,until);
  var top=jdbc.query("SELECT i.product_id,MAX(i.product_name),SUM(i.quantity),SUM(i.subtotal)"+sold+" GROUP BY i.product_id ORDER BY SUM(i.quantity) DESC,i.product_id LIMIT 10",(rs,n)->new TopProduct(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getBigDecimal(4)),id,begin,until);
  long products=jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE shop_id=?",Long.class,id);
  return new Report(start,end,"Asia/Ho_Chi_Minh",days.stream().map(RevenueDay::revenue).reduce(BigDecimal.ZERO,BigDecimal::add),days.stream().mapToLong(RevenueDay::paymentsCount).sum(),counts.values().stream().mapToLong(Long::longValue).sum(),products,totals.buyers(),counts,days,id,shop.name(),totals.total(),totals.unpaid(),items,top);
 }
}
