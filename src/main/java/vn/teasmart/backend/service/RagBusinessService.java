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
  var intent=ChatIntentClassifier.classify(request.message(),request.productId()!=null);
  String text=TeaText.normalize(request.message());
  String support=customerSupport(text);
  if(support!=null)return direct("CUSTOMER_SUPPORT",support,List.of());
  if(request.suggestedProductIds()!=null&&!request.suggestedProductIds().isEmpty()&&text.matches("(?s).*(thu hai|re hon|re nhat).*")){
   var visible=catalog.findVisibleCatalog();
   var prior=request.suggestedProductIds().stream().map(id->visible.stream().filter(p->id.equals(p.getProductId())).findFirst().orElse(null)).filter(Objects::nonNull).toList();
   if(text.contains("thu hai")){
    if(prior.size()<2)return direct("CLARIFICATION","Danh sách vừa rồi chưa có sản phẩm thứ hai. Bạn muốn mình tìm thêm theo ngân sách nào ạ?",List.of());
    var p=prior.get(1);return direct("PRODUCT_DETAIL","Sản phẩm thứ hai là "+p.getName()+". Bạn có thể mở chi tiết bên dưới để xem thêm.",List.of(new BusinessProduct(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getStockQuantity(),null,null,null)));
   }
   var pref=request.context()==null?TeaPreferenceRequest.empty():request.context();
   var eligible=visible.stream().filter(p->p.getStockQuantity()>0&&(pref.regionId()==null||pref.regionId().equals(p.getRegion().getRegionId()))&&(pref.categoryId()==null||pref.categoryId().equals(p.getCategory().getCategoryId()))&&(pref.minPrice()==null||p.getPrice().compareTo(pref.minPrice())>=0)&&(pref.maxPrice()==null||p.getPrice().compareTo(pref.maxPrice())<=0)).sorted(Comparator.comparing(vn.teasmart.backend.entity.Product::getPrice).thenComparing(vn.teasmart.backend.entity.Product::getProductId));
   var cap=prior.stream().map(vn.teasmart.backend.entity.Product::getPrice).min(java.math.BigDecimal::compareTo).orElse(null);
   var facts=eligible.filter(p->!text.contains("re hon")||cap==null||p.getPrice().compareTo(cap)<0).limit(4).map(p->new BusinessProduct(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getStockQuantity(),null,null,null)).toList();
   return new Snapshot(new TeaAdviceResponse("RULE_BASED_CATALOG_V1",facts.isEmpty()?"Chưa có sản phẩm rẻ hơn đáp ứng tiêu chí cũ. Bạn muốn điều chỉnh ngân sách hoặc vùng chè không ạ?":"Đây là những sản phẩm có mức giá thấp nhất theo tiêu chí đang chọn.",pref,List.of(),new TeaRecommendationResponse("CONTENT_BASED_V1",false,List.of())),"PRODUCT_SELECTION",facts);
  }
  String conversation=TeaText.normalize(String.join(" ",request.history()==null?List.of():request.history())+" "+request.message());
  boolean gift=text.matches("(?s).*(lam qua|qua bieu|qua tang|bieu bo|tang bo).*") || request.context()!=null&&"GIFT".equals(request.context().purpose());
  if(gift && request.productId()==null && !text.matches("(?s).*(may gio|thoi tiet|gia vang|troi.*mua).*")){
   var giftAdvice=advisor.advise(new TeaAdviceRequest(request.message(),request.preferences(),request.context()));
   var pref=giftAdvice.preferences();
   String followup=null;
   if(!conversation.matches("(?s).*(\\bbo\\b|\\bme\\b|nguoi than|thay co|doi tac|ban be).*")&&!text.contains("xem san pham"))followup="Dạ, bạn định tặng bố mẹ, người thân, thầy cô hay đối tác? Bạn muốn chọn trong ngân sách bao nhiêu ạ?";
   else if(pref.maxPrice()==null&&pref.minPrice()==null&&!text.contains("xem san pham"))followup="Bạn muốn dành ngân sách khoảng bao nhiêu cho một gói chè làm quà ạ?";
   else if(pref.strength()==null&&pref.astringency()==null&&!text.contains("xem san pham"))followup="Dạ, mình đã ghi nhận ngân sách làm quà. Người nhận thích chè đậm truyền thống hay vị nhẹ, ít chát hơn ạ?";
   if(followup!=null)return new Snapshot(new TeaAdviceResponse(giftAdvice.method(),followup,pref,giftAdvice.notices(),new TeaRecommendationResponse("CONTENT_BASED_V1",false,List.of())),"CONSULTATION",List.of());
   intent=ChatIntentClassifier.Intent.PRODUCT_RECOMMENDATION;
  }
  if(request.context()!=null && text.matches("(?s).*(re hon|re nhat|thu hai|doi y|thich che|tang bo).*"))intent=ChatIntentClassifier.Intent.PRODUCT_SEARCH;
  if(intent==ChatIntentClassifier.Intent.OUT_OF_SCOPE)return direct("OUT_OF_SCOPE","Xin lỗi, tôi là trợ lý tư vấn của TeaSmart, chuyên hỗ trợ chè Thái Nguyên và sản phẩm. Tôi chưa hỗ trợ câu hỏi ngoài phạm vi này. Bạn muốn tìm hiểu loại chè nào?",List.of());
  if(intent==ChatIntentClassifier.Intent.GREETING)return direct("GREETING","Xin chào! Bạn muốn tìm chè theo khẩu vị, ngân sách hay tìm hiểu sản phẩm đang xem?",List.of());
  if(intent==ChatIntentClassifier.Intent.UNKNOWN)return direct("CLARIFICATION","Bạn có thể nói rõ loại chè hoặc nhu cầu muốn tìm hiểu không?",List.of());
  boolean detail=Set.of(ChatIntentClassifier.Intent.PRODUCT_DETAIL,ChatIntentClassifier.Intent.PRODUCT_CONTEXT,ChatIntentClassifier.Intent.PRODUCT_PRICE,ChatIntentClassifier.Intent.PRODUCT_STOCK).contains(intent);
  if(detail && request.productId()!=null){
   var product=catalog.findVisibleCatalog().stream().filter(p->request.productId().equals(p.getProductId())).findFirst().orElseThrow(()->new vn.teasmart.backend.exception.ResourceNotFoundException("Sản phẩm không còn được phép hiển thị."));
   String reply=product.getName()+" — "+java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("vi-VN")).format(product.getPrice())+" ₫; "+product.getWeightGrams()+" g; còn "+product.getStockQuantity()+" gói tại thời điểm truy vấn.";
   if(intent==ChatIntentClassifier.Intent.PRODUCT_DETAIL){
    if(text.contains("cong dung"))reply+=" Chưa có bằng chứng xác thực về công dụng y tế riêng của sản phẩm này. Không dùng chè thay cho tư vấn hoặc điều trị y tế.";
    else if(text.contains("lam qua")||text.contains("qua bieu")||text.contains("qua tang"))reply+=" Danh mục chưa có thông tin xác minh về bao bì và mức độ phù hợp làm quà của sản phẩm này. Bạn nên xem quy cách hoặc hỏi cửa hàng trước khi chọn làm quà.";
    else if(text.contains("phu hop voi ai"))reply+=" Danh mục chưa xác định nhóm người dùng phù hợp cho sản phẩm này. Không thể suy ra mức độ phù hợp với sức khỏe chỉ từ tên hoặc giá sản phẩm.";
    else if(text.contains("che gi")||text.contains("tra gi")||text.contains("loai che"))reply+=" Phân loại trong danh mục: "+(product.getCategory()==null?"chưa bổ sung":product.getCategory().getName())+". Đây là phân loại của cửa hàng, không phải chứng nhận chất lượng.";
    else reply+=" Hương vị theo dữ liệu sản phẩm: "+(product.getTasteNote()==null||product.getTasteNote().isBlank()?"chưa bổ sung":product.getTasteNote())+". Vùng chè trong danh mục: "+product.getRegion().getName()+". Đây là thông tin danh mục, không phải chứng nhận xuất xứ.";
   }
   return direct("PRODUCT_DETAIL",reply,List.of(new BusinessProduct(product.getProductId(),product.getName(),product.getSlug(),product.getPrice(),product.getStockQuantity(),null,null,null)));
  }
  if(detail && (intent!=ChatIntentClassifier.Intent.PRODUCT_STOCK || text.matches("(?s).*(nay|dang xem|con hang ko|con hang khong).*")))return direct("CLARIFICATION","Bạn muốn hỏi sản phẩm nào? Hãy mở chi tiết sản phẩm rồi hỏi lại để tôi kiểm tra đúng giá và tồn kho.",List.of());
  if(intent==ChatIntentClassifier.Intent.PRODUCT_COMPARISON)return direct("CLARIFICATION","Bạn muốn so sánh những sản phẩm nào? Hãy cho biết rõ tên các sản phẩm; tôi chưa có đủ thông tin để so sánh chính xác.",List.of());
  if(text.matches("(?s).*(noi bat|moi nhat).*"))return direct("CLARIFICATION","Sản phẩm nổi bật hoặc mới nhất không đồng nghĩa với bán chạy. Bạn muốn xem doanh số hay tìm theo ngân sách, khẩu vị?",List.of());
  var advice=advisor.advise(new TeaAdviceRequest(request.message(),request.preferences(),request.context()));
  if(intent==ChatIntentClassifier.Intent.BEST_SELLER)return new Snapshot(advice,"BESTSELLERS",reporting.bestsellers());
  if(text.matches("(?s).*(danh gia cao|nhieu sao|diem danh gia).*"))return new Snapshot(advice,"REVIEWS",reporting.topReviews());
  if(request.context()!=null && (text.matches("(?s).*(re hon|re nhat|thu hai|doi y|thich che|tang bo).*")))intent=ChatIntentClassifier.Intent.PRODUCT_SEARCH;
  boolean business=Set.of(ChatIntentClassifier.Intent.PRODUCT_SEARCH,ChatIntentClassifier.Intent.PRODUCT_RECOMMENDATION,ChatIntentClassifier.Intent.PRODUCT_STOCK).contains(intent);
  if(business){
   var all=catalog.findVisibleCatalog();
   String referenced=text+(request.history()!=null&&text.matches("(?s).*(nay|vua noi|truoc do).*" )?" "+TeaText.normalize(String.join(" ",request.history())):"");
   var matched=all.stream().filter(p->referenced.contains(TeaText.normalize(p.getName()))).toList();
   if(!matched.isEmpty()&&text.matches("(?s).*(con hang|het hang|gia ban|bao nhieu tien|so sanh).*"))return new Snapshot(advice,"PRODUCTS",matched.stream().limit(5).map(p->new BusinessProduct(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getStockQuantity(),null,null,null)).toList());
   return new Snapshot(advice,"PRODUCTS",advice.recommendations().items().stream().map(item->new BusinessProduct(item.product().productId(),item.product().name(),item.product().slug(),item.product().price(),item.stockQuantity(),null,null,null)).toList());
  }
  return new Snapshot(advice,"KNOWLEDGE",List.of());
 }
 static String customerSupport(String text){
  if(text.matches("(?s).*(huy don|dat nham).*"))return "Bạn vào Tài khoản → Đơn hàng của tôi, mở chi tiết đơn và chọn Hủy nếu nút này khả dụng. Hiện chỉ đơn COD đang chờ xác nhận (PENDING), chưa thanh toán mới được hủy trực tiếp. Đơn đang giao hoặc đã thanh toán không hỗ trợ hủy bằng chức năng này. Mình chỉ hướng dẫn, không tự hủy đơn.";
  if(text.matches("(?s).*(theo doi don|xem don|don hang.*(o dau|trang thai)|kiem tra.*don).*"))return "Bạn đăng nhập tài khoản đã đặt hàng, vào Tài khoản → Đơn hàng của tôi rồi mở chi tiết đơn để xem trạng thái. Mình chưa tra cứu đơn cá nhân trong cuộc trò chuyện này.";
  if(text.matches("(?s).*(thanh toan|vnpay|tra tien).*"))return "TeaSmart hỗ trợ COD và VNPay Sandbox trong giao diện hiện tại. Chọn phương thức tại Checkout; VNPay chuyển sang cổng thanh toán. Quay lại hoặc đóng cổng không chứng minh đơn đã thanh toán hay đã hủy. Nếu kết quả chưa xác minh, dùng chức năng kiểm tra trạng thái và tránh gửi thanh toán lặp. Xem đơn đã tạo trong Tài khoản → Đơn hàng của tôi.";
  if(text.matches("(?s).*(cach dat hang|lam.*mua che|muon dat hang|them.*gio|lam sao.*mua).*"))return "Bạn mở sản phẩm, chọn số lượng rồi Thêm vào giỏ hoặc Mua ngay. Tại Giỏ hàng, chọn sản phẩm cần mua; ở Checkout chọn địa chỉ nhận hàng, COD hoặc VNPay và xác nhận. Với VNPay, kiểm tra kết quả xác minh trước khi thử thanh toán lại.";
  if(text.matches("(?s).*(danh gia san pham|binh luan|lam sao.*danh gia).*"))return "Bạn đăng nhập tài khoản đã mua sản phẩm, vào mục Đánh giá của tôi trong Tài khoản. Hệ thống kiểm tra sản phẩm thuộc đơn đã giao (DELIVERED); nội dung gửi lên chờ xét duyệt. Không phải mọi tài khoản đều có quyền đánh giá mọi sản phẩm.";
  return null;
 }
 private Snapshot direct(String kind,String reply,List<BusinessProduct> facts){return new Snapshot(new TeaAdviceResponse("RULE_BASED_CATALOG_V1",reply,TeaPreferenceRequest.empty(),List.of(),new TeaRecommendationResponse("CONTENT_BASED_V1",false,List.of())),kind,facts);}
}
