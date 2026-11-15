package vn.teasmart.backend.service;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.repository.ProductRepository;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.exception.*;
@Service
@Transactional(readOnly=true)
public class ProductGalleryService {
 private final JdbcTemplate jdbc;private final ProductRepository products;private final ProductService catalog;private final boolean enabled;
 public ProductGalleryService(JdbcTemplate jdbc,ProductRepository products,ProductService catalog,@Value("${teasmart.product-images.enabled:false}") boolean enabled){this.jdbc=jdbc;this.products=products;this.catalog=catalog;this.enabled=enabled;}
 public record Gallery(boolean enabled,List<String> urls){}
 public Gallery get(Long id,boolean admin){Product p=products.findById(id).orElseThrow(()->new ResourceNotFoundException("Product not found."));if(!admin)catalog.getPublicProductBySlug(p.getSlug());var urls=new ArrayList<String>();if(p.getImageUrl()!=null&&!p.getImageUrl().isBlank())urls.add(p.getImageUrl());if(enabled)for(String u:jdbc.query("SELECT image_url FROM product_images WHERE product_id=? ORDER BY sort_order,image_id",(r,i)->r.getString(1),id))if(urls.size()<3&&!urls.contains(u))urls.add(u);return new Gallery(enabled,List.copyOf(urls));}
 public static boolean validUrl(String url){if(url==null||url.length()>500||url.matches(".*[\\\\\\p{Cntrl}\\s].*"))return false;try{var u=URI.create(url);return url.startsWith("/")&&!url.startsWith("//")||Set.of("http","https").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null;}catch(RuntimeException e){return false;}}
 @Transactional public Gallery save(Long id,List<String> urls){if(!enabled)throw new ProfileException(503,"PRODUCT_GALLERY_DISABLED","Chưa bật gallery. Cần migration được duyệt trước.");if(urls==null||urls.isEmpty()||urls.size()>3||new HashSet<>(urls).size()!=urls.size()||urls.stream().anyMatch(u->!validUrl(u)))throw new ProfileException(400,"INVALID_PRODUCT_IMAGES","Cần1–3 đường dẫn ảnh hợp lệ, không trùng.");var product=products.findLockedByProductId(id).orElseThrow(()->new ResourceNotFoundException("Product not found."));jdbc.update("DELETE FROM product_images WHERE product_id=?",id);for(int i=0;i<urls.size();i++)jdbc.update("INSERT INTO product_images(product_id,image_url,sort_order,created_at) VALUES(?,?,?,?)",id,urls.get(i),i,PaymentTime.now());product.setImageUrl(urls.get(0));product.setUpdatedAt(PaymentTime.now());products.flush();return new Gallery(true,List.copyOf(urls));}
}
