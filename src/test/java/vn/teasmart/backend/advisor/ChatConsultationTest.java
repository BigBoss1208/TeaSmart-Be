package vn.teasmart.backend.advisor;
import org.junit.jupiter.api.Test;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import vn.teasmart.backend.repository.*;import vn.teasmart.backend.service.*;import vn.teasmart.backend.dto.request.*;
class ChatConsultationTest {
 @Test void giftConversationKeepsBudgetAndAsksOnlyMissingDetails(){
  var catalog=mock(TeaCatalogRepository.class);when(catalog.findVisibleCatalog()).thenReturn(List.of(TeaRecommendationEngineTest.product(1,1,1,250000,4,2),TeaRecommendationEngineTest.product(2,1,1,300000,2,1)));
  var service=new RagBusinessService(new TeaAdvisorService(catalog),catalog,mock(ChatbotReadRepository.class),mock(vn.teasmart.backend.service.ProductService.class));
  TeaPreferenceRequest context=null;var history=new ArrayList<String>();
  var questions=List.of("Tôi muốn mua chè làm quà","Tặng bố","Dưới 300k","Bố tôi thích chè đậm");
  for(int n=0;n<questions.size();n++){
   var result=service.snapshot(new RagChatRequest(questions.get(n),null,context,List.copyOf(history),false));context=result.advice().preferences();history.add(questions.get(n));
   if(n<3){assertEquals("CONSULTATION",result.kind());assertTrue(result.facts().isEmpty());}
   else{assertEquals("PRODUCTS",result.kind());assertEquals(4,context.strength());assertEquals("GIFT",context.purpose());assertTrue(result.facts().stream().allMatch(p->p.price().intValue()<300000));assertFalse(result.facts().isEmpty());}
  }
 }
 @Test void supportAnswersDoNotQueryOrdersOrInvokeProvider(){
  var advisor=mock(TeaAdvisorService.class);var catalog=mock(TeaCatalogRepository.class);var reporting=mock(ChatbotReadRepository.class);var service=new RagBusinessService(advisor,catalog,reporting,mock(vn.teasmart.backend.service.ProductService.class));
  for(String q:List.of("Cách đặt hàng?","Cách hủy đơn?","Tôi muốn xem đơn hàng","Cách thanh toán VNPay?","Làm sao đánh giá sản phẩm?")){var r=service.snapshot(new RagChatRequest(q,null,null,List.of(),true));assertEquals("CUSTOMER_SUPPORT",r.kind());assertFalse(r.advice().reply().isBlank());}
  verifyNoInteractions(advisor,catalog,reporting,mock(vn.teasmart.backend.service.ProductService.class));
 }
 @Test void followupSelectionReadsCurrentPricesAndPreservesOrder(){
  var catalog=mock(TeaCatalogRepository.class);when(catalog.findVisibleCatalog()).thenReturn(List.of(TeaRecommendationEngineTest.product(1,1,1,250000,4,2),TeaRecommendationEngineTest.product(2,1,1,150000,2,1),TeaRecommendationEngineTest.product(3,1,1,100000,2,1)));
  var service=new RagBusinessService(new TeaAdvisorService(catalog),catalog,mock(ChatbotReadRepository.class),mock(vn.teasmart.backend.service.ProductService.class));
  var second=service.snapshot(new RagChatRequest("Cho tôi xem sản phẩm thứ hai",null,null,List.of(),false,null,List.of(1L,2L)));assertEquals(2L,second.facts().get(0).productId());
  var cheaper=service.snapshot(new RagChatRequest("Loại nào rẻ hơn",null,null,List.of(),false,null,List.of(1L,2L)));assertEquals(List.of(3L),cheaper.facts().stream().map(p->p.productId()).toList());
 }

}
