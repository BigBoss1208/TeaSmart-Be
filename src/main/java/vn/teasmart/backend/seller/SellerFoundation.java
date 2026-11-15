package vn.teasmart.backend.seller;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.exception.ProfileException;
import vn.teasmart.backend.exception.ResourceNotFoundException;

/** Internal foundation only: no product publication, checkout or stock-write endpoint. */
@Service
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
@Transactional(readOnly=true)
public class SellerFoundation {
    private final JdbcTemplate jdbc;
    private final SellerService sellers;
    public SellerFoundation(JdbcTemplate jdbc, SellerService sellers) { this.jdbc=jdbc; this.sellers=sellers; }
    public record CatalogLink(Long shopId, Long storeId, Long verifiedBy, java.time.LocalDateTime verifiedAt, boolean hasProducts) {}
    public CatalogLink catalog(Long shopId) {
        return jdbc.query("SELECT shop_id,catalog_store_id,catalog_verified_by,catalog_verified_at,EXISTS(SELECT 1 FROM products p WHERE p.shop_id=shops.shop_id) has_products FROM shops WHERE shop_id=?",
            (r,n)->new CatalogLink(r.getLong(1),r.getObject(2,Long.class),r.getObject(3,Long.class),
                r.getTimestamp(4)==null?null:r.getTimestamp(4).toLocalDateTime(),r.getBoolean("has_products")),shopId)
            .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Shop not found."));
    }
    public Long requireProduct(Long userId, Long productId) {
        Long shop=sellers.myShop(userId).shopId();
        return jdbc.query("SELECT product_id FROM products WHERE product_id=? AND shop_id=?",(r,n)->r.getLong(1),productId,shop)
            .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Product not found."));
    }
    public Long requireOrder(Long userId, Long orderId) {
        Long shop=sellers.myShop(userId).shopId();
        return jdbc.query("SELECT order_id FROM orders WHERE order_id=? AND shop_id=?",(r,n)->r.getLong(1),orderId,shop)
            .stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Order not found."));
    }
    public Long requireCatalogStore(Long userId) {
        var link=catalog(sellers.myShop(userId).shopId());
        if(link.storeId()==null || link.verifiedBy()==null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM stores WHERE store_id=? AND status='ACTIVE')",Boolean.class,link.storeId())))
            throw new ProfileException(409,"CATALOG_STORE_REQUIRED","Gian hàng cần Store đang hoạt động do Admin xác minh.");
        return link.storeId();
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public CatalogLink link(Long adminId,Long shopId,Long storeId) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE user_id=? AND role='ADMIN' AND status='ACTIVE')",Boolean.class,adminId)))
            throw new ProfileException(403,"ADMIN_REQUIRED","Chỉ Admin được xác minh Store.");
        var shop=sellers.adminShop(shopId);
        // Same owner-first serialization as approval/suspension/profile edits.
        jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",shop.ownerUserId());
        var previous=catalog(shopId);
        if(storeId!=null && jdbc.queryForList("SELECT store_id FROM stores WHERE store_id=? AND status='ACTIVE' FOR UPDATE",storeId).isEmpty())
            throw new ResourceNotFoundException("Active Store not found.");
        if(Objects.equals(previous.storeId(),storeId))return previous;
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM products WHERE shop_id=?)",Boolean.class,shopId)))
            throw new ProfileException(409,"SHOP_HAS_PRODUCTS","Không đổi Store khi gian hàng đã có sản phẩm.");
        jdbc.update("UPDATE shops SET catalog_store_id=?,catalog_verified_by=?,catalog_verified_at=?,updated_at=? WHERE shop_id=?",
            storeId,storeId==null?null:adminId,storeId==null?null:vn.teasmart.backend.service.PaymentTime.now(),vn.teasmart.backend.service.PaymentTime.now(),shopId);
        return catalog(shopId);
    }
}
