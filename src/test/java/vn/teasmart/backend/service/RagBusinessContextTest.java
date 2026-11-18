package vn.teasmart.backend.service;
import java.util.*;import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.dto.request.*;
import vn.teasmart.backend.dto.response.ProductResponse;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class RagBusinessContextTest {
 private ProductResponse product(long id, String name, BigDecimal price, Long stock, String category) {
  return new ProductResponse(id,name,"fixture-"+id,"Vị dịu, hậu ngọt.",price,stock,100L,null,null,
    null,null,null,null,1L,category,2L,"Tân Cương",3L,"TeaSmart",null,null);
 }

 @Test void currentProductTakesPriorityOverPreviousLineForDetailsPriceAndStock() {
  var products=mock(ProductService.class);
  var catalog=mock(TeaCatalogRepository.class);
  var advisor=mock(TeaAdvisorService.class);
  when(products.getPublicProductById(11L)).thenReturn(product(11L,"Chè A",new BigDecimal("1150000"),0L,"Trà Nõn Tôm"));
  when(products.getPublicProductById(22L)).thenReturn(product(22L,"Chè B",new BigDecimal("200000"),8L,"Trà Quà Tặng"));
  var service=new RagBusinessService(advisor,catalog,mock(ChatbotReadRepository.class),products);
  var previous=new TeaPreferenceRequest(null,91L,null,null,null,null,null,null,null,"la bang");
  for (var pair: Map.of("Sản phẩm tôi đang mở là gì?","Chè A",
      "Chi tiết sản phẩm tôi đang mở ra xem","Tân Cương",
      "Cho tôi biết chi tiết sản phẩm này","Vị dịu, hậu ngọt",
      "Giá bao nhiêu?","1.150.000", "Còn hàng không?","hết hàng").entrySet()) {
   var result=service.snapshot(new RagChatRequest(pair.getKey(),null,previous,List.of("Chè La Bằng"),false,11L,List.of(91L)));
   assertEquals("CURRENT_PRODUCT",result.kind(),pair.getKey());
   assertTrue(result.advice().reply().contains(pair.getValue()),result.advice().reply());
   assertEquals(11L,result.facts().get(0).productId());
  }
  var gift=service.snapshot(new RagChatRequest("Loại này có hợp để làm quà không?",null,previous,List.of(),false,22L));
  assertEquals(22L,gift.facts().get(0).productId());
  assertTrue(gift.advice().reply().contains("lựa chọn làm quà"));
  var left=service.snapshot(new RagChatRequest("Sản phẩm tôi đang mở là gì?",null,previous,List.of("Chè La Bằng"),false));
  assertEquals("CLARIFICATION",left.kind());
  assertTrue(left.facts().isEmpty());
  verifyNoInteractions(advisor,catalog);
 }

 @Test void unavailableProductClarifiesAndMissingFactsAreNotInvented() {
  var products=mock(ProductService.class);
  when(products.getPublicProductById(99L)).thenThrow(new vn.teasmart.backend.exception.ResourceNotFoundException("Not public"));
  when(products.getPublicProductById(11L)).thenReturn(product(11L,"Chè A",null,null,null));
  var service=new RagBusinessService(mock(TeaAdvisorService.class),mock(TeaCatalogRepository.class),mock(ChatbotReadRepository.class),products);
  var missing=service.snapshot(new RagChatRequest("Giá bao nhiêu?",null,null,List.of(),false,99L));
  assertEquals("CLARIFICATION",missing.kind());
  assertTrue(missing.facts().isEmpty());
  var partial=service.snapshot(new RagChatRequest("Chi tiết sản phẩm này",null,null,List.of(),false,11L));
  assertEquals("CURRENT_PRODUCT",partial.kind());
  assertTrue(partial.advice().reply().contains("chưa có giá"));
  assertTrue(partial.advice().reply().contains("chưa có thông tin tồn kho"));
  assertTrue(partial.facts().isEmpty());
 }
 @Test void noProductAsksForContext(){var advisor=mock(TeaAdvisorService.class);var catalog=mock(TeaCatalogRepository.class);var reporting=mock(ChatbotReadRepository.class);var result=new RagBusinessService(advisor,catalog,reporting,mock(ProductService.class)).snapshot(new RagChatRequest("san pham nay con hang khong",null,null,List.of(),false));assertEquals("CLARIFICATION",result.kind());assertTrue(result.facts().isEmpty());verifyNoInteractions(advisor,catalog,reporting,mock(ProductService.class));}
 @Test void outOfScopeNeverQueriesBusinessOrProvider(){var advisor=mock(TeaAdvisorService.class);var catalog=mock(TeaCatalogRepository.class);var reporting=mock(ChatbotReadRepository.class);var service=new RagChatService(new RagBusinessService(advisor,catalog,reporting,mock(ProductService.class)),true,"http://127.0.0.1:1");for(String q:List.of("thoi tiet hom nay","hom nay xo so ra so gi","ban co biet nau com khong")){var response=service.answer(new RagChatRequest(q,null,null,List.of(),true));assertEquals("OUT_OF_SCOPE",response.businessKind());assertFalse(response.llmUsed());assertTrue(response.businessProducts().isEmpty());}verifyNoInteractions(advisor,catalog,reporting,mock(ProductService.class));}
 @Test void suitabilityAndGiftsDiscloseMissingEvidenceInsteadOfInventingClaims(){
  var products=mock(ProductService.class);
  when(products.getPublicProductById(11L)).thenReturn(product(11L,"Chè A",new BigDecimal("100000"),2L,null));
  var service=new RagBusinessService(mock(TeaAdvisorService.class),mock(TeaCatalogRepository.class),mock(ChatbotReadRepository.class),products);
  for(String question:List.of("Có phù hợp làm quà không?","Sản phẩm phù hợp với ai?")){
   var result=service.snapshot(new RagChatRequest(question,null,null,List.of(),false,11L));
   assertEquals("CURRENT_PRODUCT",result.kind());
   assertTrue(result.advice().reply().contains("chưa"));
  }
 }
}