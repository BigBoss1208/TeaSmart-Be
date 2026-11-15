package vn.teasmart.backend.service;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.dto.request.*;
import vn.teasmart.backend.dto.response.*;
import vn.teasmart.backend.dto.response.RagChatResponse.BusinessProduct;
@Service
public class RagBusinessService {
 private final TeaAdvisorService advisor;private final TeaCatalogRepository catalog;private final ChatbotReadRepository reporting;
 public RagBusinessService(TeaAdvisorService advisor,TeaCatalogRepository catalog,ChatbotReadRepository reporting){this.advisor=advisor;this.catalog=catalog;this.reporting=reporting;}
 public record Snapshot(TeaAdviceResponse advice,String kind,List<BusinessProduct> facts){}
 @Transactional(readOnly=true)
 public Snapshot snapshot(RagChatRequest request){
  var advice=advisor.advise(new TeaAdviceRequest(request.message(),request.preferences(),request.context()));
  String text=TeaText.normalize(request.message());
  if(text.matches("(?s).*(pha che|pha tra|bao quan|che bien|van hoa|su khac|khac nhau|trong che|sau benh|phong la|dac diem|nguon goc|lich su|chua benh|dieu tri).*"))return new Snapshot(advice,"KNOWLEDGE",List.of());
  if(text.matches("(?s).*(ban chay|mua nhieu|nhieu nhat|dang hot|top 5|top nam).*"))return new Snapshot(advice,"BESTSELLERS",reporting.bestsellers());
  if(text.matches("(?s).*(danh gia cao|nhieu sao|diem danh gia).*"))return new Snapshot(advice,"REVIEWS",reporting.topReviews());
  boolean business=text.matches("(?s).*(san pham|con hang|het hang|gia ban|bao nhieu tien|so sanh|nen mua|duoi|ngan sach|hau ngot|it chat|nhe|lam qua|bieu|dam vi|dam da|vi dam|hang ngay|moi ngay|qua tang|re hon|dat hon).*" );
  if(business){
   var all=catalog.findVisibleCatalog();
   String referenced=text+(request.history()!=null&&text.matches("(?s).*(nay|vua noi|truoc do).*" )?" "+TeaText.normalize(String.join(" ",request.history())):"");
   var matched=all.stream().filter(p->referenced.contains(TeaText.normalize(p.getName()))).toList();
   if(!matched.isEmpty()&&text.matches("(?s).*(con hang|het hang|gia ban|bao nhieu tien|so sanh).*"))return new Snapshot(advice,"PRODUCTS",matched.stream().limit(5).map(p->new BusinessProduct(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getStockQuantity(),null,null,null)).toList());
   return new Snapshot(advice,"PRODUCTS",advice.recommendations().items().stream().map(item->new BusinessProduct(item.product().productId(),item.product().name(),item.product().slug(),item.product().price(),item.stockQuantity(),null,null,null)).toList());
  }
  return new Snapshot(advice,"KNOWLEDGE",List.of());
 }
}
