package vn.teasmart.backend.seller;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
/** Read-only preflight. No auto-DDL and no effect when Seller is disabled. */
@Component
@ConditionalOnProperty(name="teasmart.seller.enabled",havingValue="true")
public class SellerSchemaCheck implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public SellerSchemaCheck(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void run(ApplicationArguments args) {
        try {
            jdbc.queryForList("SELECT application_id,applicant_user_id,shop_name,representative_name,phone,business_address,description,status,pending_applicant_id,reviewer_user_id,rejection_reason,created_at,reviewed_at FROM seller_applications WHERE 1=0");
            jdbc.queryForList("SELECT shop_id,owner_user_id,approved_application_id,name,representative_name,phone,business_address,description,logo_url,status,status_reason,status_changed_by,created_at,updated_at FROM shops WHERE 1=0");
            for(String name:java.util.List.of("uk_seller_pending_applicant","uk_shop_owner","uk_shop_application")) {
                Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='UNIQUE' AND constraint_name=?",Integer.class,name);
                if(count==null||count!=1)throw new IllegalStateException("Missing unique constraint "+name);
            }
        } catch(RuntimeException e) {
            throw new IllegalStateException("Seller schema is not ready. Verify migration 1104_seller_lite_phase1.sql on the intended database before enabling TEASMART_SELLER_ENABLED. No schema was changed.",e);
        }
    }
}
