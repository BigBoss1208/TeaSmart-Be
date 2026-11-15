package vn.teasmart.backend.seller;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(name="teasmart.seller.product-archive-enabled",havingValue="true")
public class SellerArchiveSchemaCheck implements ApplicationRunner {
 private final JdbcTemplate jdbc;private final boolean foundation;
 public SellerArchiveSchemaCheck(JdbcTemplate jdbc,@Value("${teasmart.seller.foundation-enabled:false}") boolean foundation){this.jdbc=jdbc;this.foundation=foundation;}
 public void run(ApplicationArguments args){
  if(!foundation)throw new IllegalStateException("Product Archive requires Seller Foundation.");
  try{jdbc.queryForList("SELECT seller_archived_at,seller_archived_by FROM products WHERE 1=0");for(String name:java.util.List.of("ck_seller_product_archive","fk_seller_product_archive_actor"))if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='products' AND CONSTRAINT_NAME=?",Integer.class,name)!=1)throw new IllegalStateException("Missing archive constraint");}
  catch(RuntimeException e){throw new IllegalStateException("Product Archive requires verified migration 1108 on the intended test/approved schema. No DDL was run.",e);}
 }
}
