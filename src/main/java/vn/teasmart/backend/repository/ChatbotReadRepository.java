package vn.teasmart.backend.repository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.teasmart.backend.dto.response.RagChatResponse.BusinessProduct;
@Repository
public class ChatbotReadRepository {
 private final JdbcTemplate jdbc;
 public ChatbotReadRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static final String VISIBLE=" JOIN categories c ON c.category_id=p.category_id JOIN tea_regions t ON t.region_id=p.region_id JOIN stores s ON s.store_id=p.store_id ";
 private static final String ACTIVE="p.status='ACTIVE' AND c.status='ACTIVE' AND t.status='ACTIVE' AND s.status='ACTIVE'";
 public List<BusinessProduct> bestsellers(){
  return jdbc.query("SELECT p.product_id,p.name,p.slug,p.price,p.stock_quantity,SUM(oi.quantity) units FROM order_items oi JOIN orders o ON o.order_id=oi.order_id JOIN payments pay ON pay.order_id=o.order_id JOIN products p ON p.product_id=oi.product_id"+VISIBLE+" WHERE "+ACTIVE+" AND o.order_status='DELIVERED' AND pay.payment_status='PAID' AND pay.paid_at IS NOT NULL AND pay.reconciliation_required=FALSE GROUP BY p.product_id,p.name,p.slug,p.price,p.stock_quantity ORDER BY units DESC,p.product_id DESC LIMIT 5",
   (r,i)->new BusinessProduct(r.getLong("product_id"),r.getString("name"),r.getString("slug"),r.getBigDecimal("price"),r.getLong("stock_quantity"),r.getLong("units"),null,null));
 }
 public List<BusinessProduct> topReviews(){
  return jdbc.query("SELECT p.product_id,p.name,p.slug,p.price,p.stock_quantity,AVG(r.rating) average_rating,COUNT(*) review_count FROM reviews r JOIN products p ON p.product_id=r.product_id"+VISIBLE+" WHERE "+ACTIVE+" AND r.status='APPROVED' GROUP BY p.product_id,p.name,p.slug,p.price,p.stock_quantity ORDER BY average_rating DESC,review_count DESC,p.product_id DESC LIMIT 5",
   (r,i)->new BusinessProduct(r.getLong("product_id"),r.getString("name"),r.getString("slug"),r.getBigDecimal("price"),r.getLong("stock_quantity"),null,r.getBigDecimal("average_rating"),r.getLong("review_count")));
 }
}
