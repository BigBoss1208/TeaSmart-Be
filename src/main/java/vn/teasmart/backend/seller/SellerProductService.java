package vn.teasmart.backend.seller;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.exception.*;
import vn.teasmart.backend.service.PaymentTime;

@Service
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
@Transactional(readOnly=true)
public class SellerProductService {
 @org.springframework.beans.factory.annotation.Value("${teasmart.seller.product-archive-enabled:false}") private boolean archiveEnabled;
 private final JdbcTemplate jdbc; private final SellerService sellers; private final SellerFoundation foundation;
 public SellerProductService(JdbcTemplate jdbc,SellerService sellers,SellerFoundation foundation){this.jdbc=jdbc;this.sellers=sellers;this.foundation=foundation;}
 public record Product(Long productId,String name,String description,BigDecimal price,Long stockQuantity,Long weightGrams,Long categoryId,Long regionId,String imageUrl,String status,java.time.LocalDateTime archivedAt) {}
 private static java.time.LocalDateTime archivedAt(java.sql.ResultSet r)throws java.sql.SQLException {
  var m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)if("seller_archived_at".equalsIgnoreCase(m.getColumnLabel(i))){var v=r.getTimestamp("seller_archived_at");return v==null?null:v.toLocalDateTime();}return null;
 }
 private static final org.springframework.jdbc.core.RowMapper<Product> M=(r,n)->{var archived=archivedAt(r);return new Product(r.getLong("product_id"),r.getString("name"),r.getString("description"),r.getBigDecimal("price"),r.getLong("stock_quantity"),r.getLong("weight_grams"),r.getLong("category_id"),r.getLong("region_id"),r.getString("image_url"),archived!=null?"ARCHIVED":r.getString("seller_listing_status")==null?"INACTIVE":r.getString("seller_listing_status"),archived);};
 private static void editable(Product p){if(p.archivedAt()!=null)throw new ProfileException(409,"PRODUCT_ARCHIVED","Sản phẩm đã lưu trữ; không thể chỉnh sửa, đổi trạng thái hoặc điều chỉnh kho.");}

 public Product get(Long user,Long id){return find(sellers.myShop(user).shopId(),id,false);}
 private Product find(Long shop,Long id,boolean lock){return jdbc.query("SELECT * FROM products WHERE shop_id=? AND product_id=?"+(lock?" FOR UPDATE":""),M,shop,id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Product not found."));}
 public SellerService.Page<Product> list(Long user,String q,String status,int page,int size){
  Long shop=sellers.myShop(user).shopId();if(page<0||size<1||size>50||q.length()>200||(!status.isEmpty()&&!Set.of("ACTIVE","INACTIVE","ARCHIVED").contains(status)))throw new IllegalArgumentException("Invalid filter.");
  boolean columns=archiveEnabled || jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND COLUMN_NAME='seller_archived_at'",Integer.class)==1;
  String effective=columns?"CASE WHEN seller_archived_at IS NOT NULL THEN 'ARCHIVED' ELSE COALESCE(seller_listing_status,'INACTIVE') END":"COALESCE(seller_listing_status,'INACTIVE')";
  String where=" WHERE shop_id=? AND name LIKE ? AND (?='' OR "+effective+"=?)";
  String search="%"+q+"%";long count=jdbc.queryForObject("SELECT COUNT(*) FROM products"+where,Long.class,shop,search,status,status);
  return new SellerService.Page<>(jdbc.query("SELECT * FROM products"+where+" ORDER BY product_id DESC LIMIT ? OFFSET ?",M,shop,search,status,status,size,(long)page*size),page,size,count,(count+size-1)/size);
 }
 public Map<String,Object> options(Long user){sellers.myShop(user);return Map.of("archiveEnabled",archiveEnabled,"categories",jdbc.queryForList("SELECT category_id AS id,name FROM categories WHERE status='ACTIVE' ORDER BY name"),"regions",jdbc.queryForList("SELECT region_id AS id,name FROM tea_regions WHERE status='ACTIVE' ORDER BY name"));}
 private Long validate(Long user,SellerProductRequests.Product p){
  Long store=foundation.requireCatalogStore(user);
  if(p.imageUrl()!=null&&!p.imageUrl().isBlank()&&!vn.teasmart.backend.service.ProductGalleryService.validUrl(p.imageUrl().trim()))throw new IllegalArgumentException("URL ảnh sản phẩm không hợp lệ.");
  if(jdbc.queryForList("SELECT category_id FROM categories WHERE category_id=? AND status='ACTIVE'",p.categoryId()).isEmpty()||jdbc.queryForList("SELECT region_id FROM tea_regions WHERE region_id=? AND status='ACTIVE'",p.regionId()).isEmpty())throw new IllegalArgumentException("Danh mục hoặc vùng chè không hợp lệ.");return store;
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Product create(Long user,SellerProductRequests.Product p){
  Long shop=sellers.lockMyShop(user).shopId();Long store=validate(user,p);String slug="seller-"+shop+"-"+UUID.randomUUID();
  jdbc.update("INSERT INTO products(shop_id,store_id,category_id,region_id,name,slug,description,price,image_url,stock_quantity,weight_grams,status,seller_listing_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,0,?,'INACTIVE','INACTIVE',?,?)",shop,store,p.categoryId(),p.regionId(),p.name(),slug,p.description(),p.price(),image(p.imageUrl()),p.weightGrams(),PaymentTime.now(),PaymentTime.now());
  return jdbc.queryForObject("SELECT * FROM products WHERE slug=?",M,slug);
 }
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Product update(Long user,Long id,SellerProductRequests.Product p){
  Long shop=sellers.lockMyShop(user).shopId();editable(find(shop,id,true));Long store=validate(user,p);
  jdbc.update("UPDATE products SET store_id=?,category_id=?,region_id=?,name=?,description=?,price=?,image_url=?,weight_grams=?,updated_at=? WHERE product_id=? AND shop_id=?",store,p.categoryId(),p.regionId(),p.name(),p.description(),p.price(),image(p.imageUrl()),p.weightGrams(),PaymentTime.now(),id,shop);return find(shop,id,false);
 }
 private static String image(String url){return url==null||url.isBlank()?null:url.trim();}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Product status(Long user,Long id,String status){Long shop=sellers.lockMyShop(user).shopId();editable(find(shop,id,true));if("ACTIVE".equals(status))foundation.requireCatalogStore(user);jdbc.update("UPDATE products SET seller_listing_status=?,updated_at=? WHERE product_id=? AND shop_id=?",status,PaymentTime.now(),id,shop);return find(shop,id,false);}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Product archive(Long user,Long id){
  Long shop=sellers.lockMyShop(user).shopId();Product p=find(shop,id,true);
  if(!archiveEnabled)throw new ProfileException(503,"PRODUCT_ARCHIVE_DISABLED","Lưu trữ sản phẩm chưa được mở trên môi trường này.");
  if(p.archivedAt()!=null)return p;
  var now=PaymentTime.now();
  jdbc.update("UPDATE products SET seller_listing_status='INACTIVE',seller_archived_at=?,seller_archived_by=?,updated_at=? WHERE product_id=? AND shop_id=?",now,user,now,id,shop);
  return find(shop,id,false);
 }
 public record Adjustment(Long adjustmentId,Long productId,long delta,String reason,long stockAfter) {}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Adjustment adjust(Long user,Long id,String key,SellerProductRequests.Stock request){
  if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}")||request.delta()==0)throw new IllegalArgumentException("Invalid adjustment key or delta.");
  Long shop=sellers.lockMyShop(user).shopId();Product product=find(shop,id,true);
  var previous=jdbc.query("SELECT adjustment_id,product_id,delta,reason,stock_after FROM seller_stock_adjustments WHERE shop_id=? AND request_key=?",(r,n)->new Adjustment(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getLong(5)),shop,key);
  if(!previous.isEmpty()){var p=previous.get(0);if(!p.productId().equals(id)||p.delta()!=request.delta()||!p.reason().equals(request.reason()))throw new ProfileException(409,"IDEMPOTENCY_KEY_REUSED","Mã yêu cầu đã dùng cho thay đổi khác.");return p;}
  editable(product);
  long next=product.stockQuantity()+request.delta();if(next<0||next>4294967295L)throw new ProfileException(409,"INVALID_STOCK","Tồn khả dụng không đủ hoặc vượt giới hạn.");
  if(request.delta()>0) {
      Long held=jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM checkout_session_items i JOIN checkout_sessions s ON s.checkout_session_id=i.checkout_session_id WHERE i.product_id=? AND s.reservation_status='HELD'",Long.class,id);
      if(next+held>4294967295L)throw new ProfileException(409,"INVALID_STOCK","Cần giữ đủ chỗ để hoàn lại hàng đang giữ.");
  }
  jdbc.update("UPDATE products SET stock_quantity=?,updated_at=? WHERE product_id=? AND shop_id=?",next,PaymentTime.now(),id,shop);
  jdbc.update("INSERT INTO seller_stock_adjustments(shop_id,product_id,actor_user_id,request_key,delta,reason,stock_after,created_at) VALUES(?,?,?,?,?,?,?,?)",shop,id,user,key,request.delta(),request.reason(),next,PaymentTime.now());
  return jdbc.queryForObject("SELECT adjustment_id,product_id,delta,reason,stock_after FROM seller_stock_adjustments WHERE shop_id=? AND request_key=?",(r,n)->new Adjustment(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getLong(5)),shop,key);
 }
}
