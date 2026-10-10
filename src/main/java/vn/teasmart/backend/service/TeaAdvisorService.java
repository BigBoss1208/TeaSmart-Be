package vn.teasmart.backend.service;

import java.util.*;
import java.text.NumberFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.repository.TeaCatalogRepository;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.dto.request.*;
import vn.teasmart.backend.dto.response.*;
import vn.teasmart.backend.exception.ResourceNotFoundException;

@Service
@Transactional(readOnly=true)
public class TeaAdvisorService {
    private final TeaCatalogRepository catalog;
    public TeaAdvisorService(TeaCatalogRepository catalog) {this.catalog=catalog;}
    public TeaRecommendationResponse related(Long productId,int limit,String profile) {
        var products=catalog.findVisibleCatalog();Product seed=null;
        if(productId!=null)seed=products.stream().filter(p->productId.equals(p.getProductId())).findFirst()
                .orElseThrow(()->new ResourceNotFoundException("Product not found."));
        var q=new TeaPreferenceRequest(null,null,null,null,"STRONG".equals(profile)?4:null,
                "LOW_ASTRINGENCY".equals(profile)?1:null,"AROMATIC".equals(profile)?4:null,"SWEET".equals(profile)?4:null,
                "DAILY".equals(profile)||"GIFT".equals(profile)?profile:null);
        return TeaRecommendationEngine.rank(products,seed,q,limit);
    }
    public TeaRecommendationResponse preferences(TeaPreferenceRequest request,int limit) {
        var products=catalog.findVisibleCatalog();validate(request,products);
        return TeaRecommendationEngine.rank(products,null,request,limit);
    }
    public TeaAdviceResponse advise(TeaAdviceRequest request) {
        if(request.message().matches("(?is).*\\bBearer\\s+\\S+.*")||request.message().matches("(?s).*eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+.*"))throw new IllegalArgumentException("Credentials are not accepted in chat text.");
        var products=catalog.findVisibleCatalog();
        var parsed=TeaIntentParser.parse(request.message(),request.preferences(),products);
        var preferences=merge(parsed.preferences(),request.context());
        validate(preferences,products);
        var result=TeaRecommendationEngine.rank(products,null,preferences,4,request.message());
        String reply;
        if(result.items().isEmpty())reply="Hiện chưa có sản phẩm còn hàng phù hợp các điều kiện đã chọn. Bạn có thể đổi ngân sách, danh mục hoặc vùng chè; mình không tự nới ngân sách.";
        else {
            var format=NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"));
            StringBuilder text=new StringBuilder(result.fallback()?"Chưa đủ dữ liệu để xác định khẩu vị. Đây là các sản phẩm còn hàng trong danh mục hiện tại:":"Bạn có thể tham khảo các sản phẩm sau theo nhu cầu đã cung cấp:");
            for(var item:result.items())text.append("\n• ").append(item.product().name()).append(" — ")
                    .append(format.format(item.product().price())).append(" ₫ / ").append(item.product().weightGrams()).append(" g; còn ").append(item.stockQuantity()).append(" gói.");
            reply=text.append("\nGiá và tồn kho là dữ liệu lúc tư vấn; giỏ hàng/checkout sẽ kiểm tra lại.").toString();
        }
        return new TeaAdviceResponse("RULE_BASED_CATALOG_V1",reply,preferences,parsed.notices(),result);
    }
    private TeaPreferenceRequest merge(TeaPreferenceRequest current,TeaPreferenceRequest previous) {
        if(previous==null)return current;
        return new TeaPreferenceRequest(or(current.categoryId(),previous.categoryId()),or(current.regionId(),previous.regionId()),
                current.minPrice()!=null||current.maxPrice()!=null?current.minPrice():previous.minPrice(),current.minPrice()!=null||current.maxPrice()!=null?current.maxPrice():previous.maxPrice(),or(current.strength(),previous.strength()),
                or(current.astringency(),previous.astringency()),or(current.aroma(),previous.aroma()),or(current.aftertaste(),previous.aftertaste()),or(current.purpose(),previous.purpose()));
    }
    private <T> T or(T a,T b) {return a==null?b:a;}
    private void validate(TeaPreferenceRequest q,List<Product> products) {
        if(q.minPrice()!=null&&q.maxPrice()!=null&&q.minPrice().compareTo(q.maxPrice())>0)throw new IllegalArgumentException("Invalid price range.");
        if(q.categoryId()!=null&&products.stream().noneMatch(p->q.categoryId().equals(p.getCategory().getCategoryId())))throw new ResourceNotFoundException("No public products in the requested category.");
        if(q.regionId()!=null&&products.stream().noneMatch(p->q.regionId().equals(p.getRegion().getRegionId())))throw new ResourceNotFoundException("No public products in the requested region.");
    }
}
