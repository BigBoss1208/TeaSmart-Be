package vn.teasmart.backend.advisor;
import org.junit.jupiter.api.Test;import java.util.*;import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import vn.teasmart.backend.repository.*;import vn.teasmart.backend.service.*;import vn.teasmart.backend.dto.request.*;
class ChatTopicSwitchTest {
 @Test void explicitTopicsOverrideGiftContext(){
  var catalog=mock(TeaCatalogRepository.class);when(catalog.findVisibleCatalog()).thenReturn(List.of(TeaRecommendationEngineTest.product(1,1,1,250000,4,2)));
  var reporting=mock(ChatbotReadRepository.class);when(reporting.bestsellers()).thenReturn(List.of());
  var service=new RagBusinessService(new TeaAdvisorService(catalog),catalog,reporting,mock(vn.teasmart.backend.service.ProductService.class));
  var context=new TeaPreferenceRequest(null,null,null,new BigDecimal("1000000"),null,null,null,null,"GIFT");
  var questions=Map.of("chào b","GREETING","chào","GREETING","san pham hot","BESTSELLERS","Chè bán chạy","BESTSELLERS","Cách pha chè","KNOWLEDGE","cach huy don","CUSTOMER_SUPPORT","Thời tiết hôm nay","OUT_OF_SCOPE");
  for(var q:questions.entrySet()){var result=service.snapshot(new RagChatRequest(q.getKey(),null,context,List.of("Tôi muốn mua chè làm quà"),null));assertEquals(q.getValue(),result.kind(),q.getKey());}
 }
 @Test void greetingVariantsNeverBecomeProducts(){for(String q:List.of("chào b","Chào bạn","chào","xin chào","hello","hi"))assertEquals(ChatIntentClassifier.Intent.GREETING,ChatIntentClassifier.classify(q,false),q);}
 @Test void lowAstringencyUsesVerifiedMetadataAndOrdersOneBeforeTwo(){
  var catalog=mock(TeaCatalogRepository.class);
  when(catalog.findVisibleCatalog()).thenReturn(List.of(TeaRecommendationEngineTest.product(1,1,1,100000,4,1),TeaRecommendationEngineTest.product(2,1,1,290000,4,2),TeaRecommendationEngineTest.product(3,1,1,100000,4,3),TeaRecommendationEngineTest.product(4,1,1,100000,4,null)));
  var result=new TeaAdvisorService(catalog).adviseChat(new TeaAdviceRequest("ít chát dưới 300k",null,null));
  assertEquals(List.of(1L,2L),result.recommendations().items().stream().map(i->i.product().productId()).toList());
 }
 @Test void chatReturnsAllMatchesWhileLegacyAdviceRetainsItsLimit(){
  var catalog=mock(TeaCatalogRepository.class);var products=new ArrayList<vn.teasmart.backend.entity.Product>();for(int i=1;i<=12;i++)products.add(TeaRecommendationEngineTest.product(i,1,1,100000,4,1));when(catalog.findVisibleCatalog()).thenReturn(products);
  var advisor=new TeaAdvisorService(catalog);var request=new TeaAdviceRequest("chè dưới 300k",null,null);assertEquals(12,advisor.adviseChat(request).recommendations().items().size());assertEquals(4,advisor.advise(request).recommendations().items().size());
 }

 @Test void cheaperFollowupDoesNotDropLowAstringencyConstraint(){
  var catalog=mock(TeaCatalogRepository.class);when(catalog.findVisibleCatalog()).thenReturn(List.of(TeaRecommendationEngineTest.product(1,1,1,250000,4,1),TeaRecommendationEngineTest.product(2,1,1,100000,4,4),TeaRecommendationEngineTest.product(3,1,1,150000,4,2)));
  var pref=new TeaPreferenceRequest(null,null,null,new BigDecimal("300000"),null,1,null,null,null);
  var result=new RagBusinessService(new TeaAdvisorService(catalog),catalog,mock(ChatbotReadRepository.class),mock(vn.teasmart.backend.service.ProductService.class)).snapshot(new RagChatRequest("rẻ hơn",null,pref,List.of(),null,null,List.of(1L)));
  assertEquals(List.of(3L),result.facts().stream().map(p->p.productId()).toList());
 }

}
