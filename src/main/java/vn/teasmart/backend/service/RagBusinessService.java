package vn.teasmart.backend.service;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.dto.request.*;
import vn.teasmart.backend.dto.response.*;
import vn.teasmart.backend.dto.response.RagChatResponse.BusinessProduct;
@Service
public class RagBusinessService  {
    private final TeaAdvisorService advisor;
    private final TeaCatalogRepository catalog;
    private final ChatbotReadRepository reporting;
    public RagBusinessService(TeaAdvisorService advisor,TeaCatalogRepository catalog,ChatbotReadRepository reporting) {
        this.advisor=advisor;
        this.catalog=catalog;
        this.reporting=reporting;
    }
    public record Snapshot(TeaAdviceResponse advice,String kind,List<BusinessProduct> facts) {
    }
    @Transactional(readOnly=true)
    public Snapshot snapshot(RagChatRequest request) {
        var intent=ChatIntentClassifier.classify(request.message(),request.productId()!=null);
        String text=TeaText.normalize(request.message());
        boolean followUp=ChatConversation.isFollowUp(text)&&!ChatConversation.startsNewNeed(text);
        var previous=followUp?request.context():null;
        if(followUp && intent==ChatIntentClassifier.Intent.OUT_OF_SCOPE)intent=ChatIntentClassifier.Intent.PRODUCT_SEARCH;
        if(intent==ChatIntentClassifier.Intent.GREETING)return direct("GREETING","Chào bạn! 🍃 Mình là TeaSmart AI. Hôm nay bạn muốn tìm chè theo khẩu vị, ngân sách hay cần mình hỗ trợ đặt hàng ạ?",List.of());
        String support=ChatSupportReplies.answer(text);
        if(support!=null)return direct("CUSTOMER_SUPPORT",support,List.of());
        if(request.suggestedProductIds()!=null&&!request.suggestedProductIds().isEmpty()&&text.matches("(?s).*(thu hai|re hon|re nhat).*")) {
            var visible=catalog.findVisibleCatalog();
            var prior=request.suggestedProductIds().stream().map(id->visible.stream().filter(p->id.equals(p.getProductId())).findFirst().orElse(null)).filter(Objects::nonNull).toList();
            if(text.contains("thu hai")) {
                if(prior.size()<2)return direct("CLARIFICATION","Danh sách vừa rồi chưa có sản phẩm thứ hai. Bạn muốn mình tìm thêm theo ngân sách nào ạ?",List.of());
                var p=prior.get(1);
                return direct("PRODUCT_DETAIL","Sản phẩm thứ hai là "+p.getName()+". Bạn có thể mở chi tiết bên dưới để xem thêm.",List.of(new BusinessProduct(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getStockQuantity(),null,null,null)));
            }
            var pref=previous==null?TeaPreferenceRequest.empty():previous;
            var eligible=visible.stream().filter(p->p.getStockQuantity()>0&&(!Integer.valueOf(1).equals(pref.astringency())||p.getAstringencyLevel()!=null&&p.getAstringencyLevel()>=1&&p.getAstringencyLevel()<=2)&&(pref.regionId()==null||pref.regionId().equals(p.getRegion().getRegionId()))&&(pref.categoryId()==null||pref.categoryId().equals(p.getCategory().getCategoryId()))&&(pref.minPrice()==null||p.getPrice().compareTo(pref.minPrice())>=0)&&(pref.maxPrice()==null||p.getPrice().compareTo(pref.maxPrice())<=0)).sorted(Comparator.comparing(vn.teasmart.backend.entity.Product::getPrice).thenComparing(vn.teasmart.backend.entity.Product::getProductId));
            var cap=prior.stream().map(vn.teasmart.backend.entity.Product::getPrice).min(java.math.BigDecimal::compareTo).orElse(null);
            var facts=eligible.filter(p->!text.contains("re hon")||cap==null||p.getPrice().compareTo(cap)<0).limit(4).map(p->new BusinessProduct(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getStockQuantity(),null,null,null)).toList();
            return new Snapshot(new TeaAdviceResponse("RULE_BASED_CATALOG_V1",facts.isEmpty()?"Chưa có sản phẩm rẻ hơn đáp ứng tiêu chí cũ. Bạn muốn điều chỉnh ngân sách hoặc vùng chè không ạ?":"Đây là những sản phẩm có mức giá thấp nhất theo tiêu chí đang chọn.",pref,List.of(),new TeaRecommendationResponse("CONTENT_BASED_V1",false,List.of())),"PRODUCT_SELECTION",facts);
        }
        String conversation=TeaText.normalize(String.join(" ",request.history()==null?List.of():request.history())+" "+request.message());
        boolean gift=text.matches("(?s).*(lam qua|qua bieu|qua tang|bieu bo|tang bo).*") || previous!=null&&"GIFT".equals(previous.purpose());
        if(gift && request.productId()==null && !Set.of(ChatIntentClassifier.Intent.BEST_SELLER,ChatIntentClassifier.Intent.BREWING_GUIDE,ChatIntentClassifier.Intent.TEA_KNOWLEDGE,ChatIntentClassifier.Intent.TEA_ORIGIN,ChatIntentClassifier.Intent.GREETING).contains(intent) && intent!=ChatIntentClassifier.Intent.OUT_OF_SCOPE) {
            var giftAdvice=advisor.adviseChat(new TeaAdviceRequest(request.message(),request.preferences(),previous));
            var pref=giftAdvice.preferences();
            String followup=null;
            if(!conversation.matches("(?s).*(\\bbo\\b|\\bme\\b|nguoi than|thay co|doi tac|ban be).*")&&!text.contains("xem san pham"))followup="Dạ, TeaSmart rất vui được giúp bạn chọn một món quà ý nghĩa! 🍃 Bạn định tặng người thân, thầy cô hay đối tác ạ? Bạn muốn chọn trong khoảng ngân sách bao nhiêu?";
            else if(pref.maxPrice()==null&&pref.minPrice()==null&&!text.contains("xem san pham"))followup="Bạn muốn dành ngân sách khoảng bao nhiêu cho một gói chè làm quà ạ?";
            else if(pref.strength()==null&&pref.astringency()==null&&!text.contains("xem san pham"))followup="Dạ, mình đã ghi nhận ngân sách làm quà. Người nhận thích chè đậm truyền thống hay vị nhẹ, ít chát hơn ạ?";
            if(followup!=null)return new Snapshot(new TeaAdviceResponse(giftAdvice.method(),followup,pref,giftAdvice.notices(),new TeaRecommendationResponse("CONTENT_BASED_V1",false,List.of())),"CONSULTATION",List.of());
            intent=ChatIntentClassifier.Intent.PRODUCT_RECOMMENDATION;
        }
        if(previous!=null && text.matches("(?s).*(re hon|re nhat|thu hai|doi y|thich che|tang bo).*"))intent=ChatIntentClassifier.Intent.PRODUCT_SEARCH;
        if(intent==ChatIntentClassifier.Intent.OUT_OF_SCOPE)return direct("OUT_OF_SCOPE","Xin lỗi, tôi là trợ lý tư vấn của TeaSmart, chuyên hỗ trợ chè Thái Nguyên và sản phẩm. Tôi chưa hỗ trợ câu hỏi ngoài phạm vi này. Bạn muốn tìm hiểu loại chè nào?",List.of());
        if(intent==ChatIntentClassifier.Intent.GREETING)return direct("GREETING","Xin chào! Bạn muốn tìm chè theo khẩu vị, ngân sách hay tìm hiểu sản phẩm đang xem?",List.of());
        if(intent==ChatIntentClassifier.Intent.UNKNOWN)return direct("CLARIFICATION","Bạn có thể nói rõ loại chè hoặc nhu cầu muốn tìm hiểu không?",List.of());
        boolean detail=Set.of(ChatIntentClassifier.Intent.PRODUCT_DETAIL,ChatIntentClassifier.Intent.PRODUCT_CONTEXT,ChatIntentClassifier.Intent.PRODUCT_PRICE,ChatIntentClassifier.Intent.PRODUCT_STOCK).contains(intent);
        if(detail && request.productId()!=null) {
            var product=catalog.findVisibleCatalog().stream().filter(p->request.productId().equals(p.getProductId())).findFirst().orElseThrow(()->new vn.teasmart.backend.exception.ResourceNotFoundException("Sản phẩm không còn được phép hiển thị."));
            String reply=product.getName()+" — "+java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("vi-VN")).format(product.getPrice())+" ₫; "+product.getWeightGrams()+" g; còn "+product.getStockQuantity()+" gói tại thời điểm truy vấn.";
            if(intent==ChatIntentClassifier.Intent.PRODUCT_DETAIL) {
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
        var advice=advisor.adviseChat(new TeaAdviceRequest(request.message(),request.preferences(),previous));
        if(intent==ChatIntentClassifier.Intent.BEST_SELLER)return new Snapshot(advice,"BESTSELLERS",reporting.bestsellers());
        if(text.matches("(?s).*(danh gia cao|nhieu sao|diem danh gia).*"))return new Snapshot(advice,"REVIEWS",reporting.topReviews());
        if(previous!=null && (text.matches("(?s).*(re hon|re nhat|thu hai|doi y|thich che|tang bo).*")))intent=ChatIntentClassifier.Intent.PRODUCT_SEARCH;
        boolean business=Set.of(ChatIntentClassifier.Intent.PRODUCT_SEARCH,ChatIntentClassifier.Intent.PRODUCT_RECOMMENDATION,ChatIntentClassifier.Intent.PRODUCT_STOCK).contains(intent);
        if(business) {
            var all=catalog.findVisibleCatalog();
            String referenced=text+(request.history()!=null&&text.matches("(?s).*(nay|vua noi|truoc do).*" )?" "+TeaText.normalize(String.join(" ",request.history())):"");
            var matched=all.stream().filter(p->referenced.contains(TeaText.normalize(p.getName()))).toList();
            if(!matched.isEmpty()&&text.matches("(?s).*(con hang|het hang|gia ban|bao nhieu tien|so sanh).*"))return new Snapshot(advice,"PRODUCTS",matched.stream().limit(5).map(p->new BusinessProduct(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getStockQuantity(),null,null,null)).toList());
            return new Snapshot(advice,"PRODUCTS",advice.recommendations().items().stream().map(item->new BusinessProduct(item.product().productId(),item.product().name(),item.product().slug(),item.product().price(),item.stockQuantity(),null,null,null)).toList());
        }
        return new Snapshot(advice,"KNOWLEDGE",List.of());
    }
    private Snapshot direct(String kind,String reply,List<BusinessProduct> facts) {
        return new Snapshot(new TeaAdviceResponse("RULE_BASED_CATALOG_V1",reply,TeaPreferenceRequest.empty(),List.of(),new TeaRecommendationResponse("CONTENT_BASED_V1",false,List.of())),kind,facts);
    }
}
