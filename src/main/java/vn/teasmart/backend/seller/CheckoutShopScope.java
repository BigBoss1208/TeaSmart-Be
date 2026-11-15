package vn.teasmart.backend.seller;

import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.exception.OrderConflictException;
import vn.teasmart.backend.exception.ResourceNotFoundException;

/** Called within the checkout transaction. Legacy installations do not instantiate this bean. */
@Component
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
public class CheckoutShopScope {
    public record Snapshot(Long id, String name) {}
    public static final Snapshot LEGACY = new Snapshot(null,null);
    private final JdbcTemplate jdbc;
    public CheckoutShopScope(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Snapshot resolve(Collection<Long> ids) {
        Long group=null;
        boolean first=true;
        for (Long id : ids.stream().sorted().toList()) {
            var rows=jdbc.query("SELECT shop_id FROM products WHERE product_id=? FOR UPDATE",
                    (r,n)->r.getObject(1,Long.class),id);
            if(rows.isEmpty())throw new ResourceNotFoundException("Product not found.");
            Long current=rows.get(0);
            if(!first && !java.util.Objects.equals(group,current))
                throw new OrderConflictException("MULTI_SHOP_CHECKOUT","Vui lòng chỉ chọn sản phẩm của một gian hàng.");
            first=false; group=current;
        }
        if(group==null)return LEGACY;
        Snapshot snapshot=null;
        for(Long id:ids) {
            List<Snapshot> eligible=jdbc.query("""
                SELECT s.shop_id,s.name FROM products p
                JOIN shops s ON s.shop_id=p.shop_id
                JOIN stores st ON st.store_id=s.catalog_store_id AND st.store_id=p.store_id
                JOIN users u ON u.user_id=s.owner_user_id
                JOIN categories c ON c.category_id=p.category_id
                JOIN tea_regions t ON t.region_id=p.region_id
                WHERE p.product_id=? AND s.status='ACTIVE' AND u.status='ACTIVE' AND u.role='CUSTOMER'
                AND st.status='ACTIVE' AND s.catalog_verified_by IS NOT NULL AND s.catalog_verified_at IS NOT NULL
                AND c.status='ACTIVE' AND t.status='ACTIVE' AND p.seller_listing_status='ACTIVE'
                FOR SHARE OF s,st,c,t
                """,(r,n)->new Snapshot(r.getLong(1),r.getString(2)),id);
            if(eligible.isEmpty())throw new OrderConflictException("PRODUCT_UNAVAILABLE","Gian hàng hoặc sản phẩm chưa sẵn sàng bán.");
            snapshot=eligible.get(0);
        }
        return snapshot;
    }
    public Snapshot product(Long id) {
        return jdbc.queryForObject("SELECT p.shop_id,s.name FROM products p LEFT JOIN shops s ON s.shop_id=p.shop_id WHERE p.product_id=?",
                (r,n)->new Snapshot(r.getObject(1,Long.class),r.getString(2)),id);
    }
    /** Re-checks every publication prerequisite at the locked product's checkout boundary. */
    public boolean activeSellerListing(Long productId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM products p
              JOIN shops s ON s.shop_id=p.shop_id
              JOIN users u ON u.user_id=s.owner_user_id
              JOIN stores st ON st.store_id=s.catalog_store_id AND st.store_id=p.store_id
              JOIN categories c ON c.category_id=p.category_id
              JOIN tea_regions r ON r.region_id=p.region_id
             WHERE p.product_id=? AND p.status='INACTIVE' AND p.seller_listing_status='ACTIVE'
               AND s.status='ACTIVE' AND u.status='ACTIVE' AND u.role='CUSTOMER'
               AND s.catalog_verified_by IS NOT NULL AND s.catalog_verified_at IS NOT NULL
               AND st.status='ACTIVE' AND c.status='ACTIVE' AND r.status='ACTIVE')
            """, Boolean.class, productId));
    }
    public Snapshot session(Long id) {
        return jdbc.queryForObject("SELECT shop_id,shop_name FROM checkout_sessions WHERE checkout_session_id=?",
                (r,n)->new Snapshot(r.getObject(1,Long.class),r.getString(2)),id);
    }
    public void saveSession(Long id,Snapshot s) {
        if(s.id()!=null)jdbc.update("UPDATE checkout_sessions SET shop_id=?,shop_name=? WHERE checkout_session_id=?",s.id(),s.name(),id);
    }
    public void saveOrder(Long id,Snapshot s) {
        if(s.id()!=null)jdbc.update("UPDATE orders SET shop_id=?,shop_name=? WHERE order_id=?",s.id(),s.name(),id);
    }
}
