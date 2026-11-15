package vn.teasmart.backend.seller;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
public class SellerFoundationSchemaCheck implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SellerService sellers;
    public SellerFoundationSchemaCheck(JdbcTemplate jdbc,SellerService sellers) { this.jdbc=jdbc;this.sellers=sellers; }
    public void run(ApplicationArguments args) {
        if(!sellers.enabled())throw new IllegalStateException("Seller foundation requires Seller enabled.");
        try {
            jdbc.queryForList("SELECT catalog_store_id,catalog_verified_by,catalog_verified_at FROM shops WHERE 1=0");
            jdbc.queryForList("SELECT shop_id FROM products WHERE 1=0");
            jdbc.queryForList("SELECT seller_listing_status FROM products WHERE 1=0");
            jdbc.queryForList("SELECT adjustment_id,shop_id,product_id,actor_user_id,request_key,delta,reason,stock_after,created_at FROM seller_stock_adjustments WHERE 1=0");
            for(String name:java.util.List.of("ck_seller_private_product","uk_seller_stock_key"))
                if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name=?",Integer.class,name)!=1)
                    throw new IllegalStateException("Missing constraint: "+name);
            jdbc.queryForList("SELECT shop_id,shop_name FROM orders WHERE 1=0");
            jdbc.queryForList("SELECT shop_id,shop_name FROM checkout_sessions WHERE 1=0");
            for(String name:java.util.List.of("fk_shop_catalog_store","fk_shop_catalog_verifier","fk_product_shop","fk_order_shop","fk_session_shop"))
                if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='FOREIGN KEY' AND constraint_name=?",Integer.class,name)!=1)
                    throw new IllegalStateException("Missing constraint: "+name);
        } catch(RuntimeException e) { throw new IllegalStateException("Seller foundation requires verified migrations 1105 and 1106 on this database. No DDL was run.",e); }
    }
}
