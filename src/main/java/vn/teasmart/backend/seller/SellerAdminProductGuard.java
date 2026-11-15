package vn.teasmart.backend.seller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.dto.request.AdminProductRequest;
import vn.teasmart.backend.exception.ProfileException;
@Component
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
public class SellerAdminProductGuard {
 private final JdbcTemplate jdbc;
 public SellerAdminProductGuard(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private boolean seller(Long id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT shop_id IS NOT NULL FROM products WHERE product_id=?",Boolean.class,id));}
 public void update(Product product,AdminProductRequest request){
  if(seller(product.getProductId())&&(!product.getStockQuantity().equals(request.stockQuantity())||!product.getStore().getStoreId().equals(request.storeId())||!"INACTIVE".equals(request.status())))
   throw new ProfileException(409,"SELLER_PRODUCT_PROTECTED","Sản phẩm Seller chưa mở công khai; kho phải điều chỉnh bằng delta và Store do Admin xác minh cho Shop.");
 }
 public void status(Long id,String status){if(seller(id)&&!"INACTIVE".equals(status))throw new ProfileException(409,"SELLER_NOT_PUBLIC","Sản phẩm Seller chưa được mở bán công khai.");}
}
