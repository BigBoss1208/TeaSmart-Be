package vn.teasmart.backend.service;
import java.util.*;import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.repository.*;import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.dto.request.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class RagBusinessContextTest {
 @Test void contextUsesCurrentDatabaseRecordAndDoesNotReusePreviousProduct(){var catalog=mock(TeaCatalogRepository.class);var reporting=mock(ChatbotReadRepository.class);var advisor=mock(TeaAdvisorService.class);var a=mock(Product.class);var b=mock(Product.class);when(a.getProductId()).thenReturn(11L);when(b.getProductId()).thenReturn(22L);when(a.getName()).thenReturn("Chè A");when(b.getName()).thenReturn("Chè B");when(a.getPrice()).thenReturn(new BigDecimal("100000"));when(b.getPrice()).thenReturn(new BigDecimal("200000"));when(a.getStockQuantity()).thenReturn(0L);when(b.getStockQuantity()).thenReturn(8L);when(catalog.findVisibleCatalog()).thenReturn(List.of(a,b));var service=new RagBusinessService(advisor,catalog,reporting);
 for(long id:List.of(11L,22L)){var result=service.snapshot(new RagChatRequest("gia bao nhieu",null,null,List.of(),false,id));assertEquals(id,result.facts().get(0).productId());assertEquals(id==11?0L:8L,result.facts().get(0).stockQuantity());assertEquals(id==11?100000:200000,result.facts().get(0).price().intValue());}verifyNoInteractions(advisor,reporting);
 assertThrows(vn.teasmart.backend.exception.ResourceNotFoundException.class,()->service.snapshot(new RagChatRequest("gia bao nhieu",null,null,List.of(),false,999L)));
 }
 @Test void noProductAsksForContext(){var advisor=mock(TeaAdvisorService.class);var catalog=mock(TeaCatalogRepository.class);var reporting=mock(ChatbotReadRepository.class);var result=new RagBusinessService(advisor,catalog,reporting).snapshot(new RagChatRequest("san pham nay con hang khong",null,null,List.of(),false));assertEquals("CLARIFICATION",result.kind());assertTrue(result.facts().isEmpty());verifyNoInteractions(advisor,catalog,reporting);}
 @Test void outOfScopeNeverQueriesBusinessOrProvider(){var advisor=mock(TeaAdvisorService.class);var catalog=mock(TeaCatalogRepository.class);var reporting=mock(ChatbotReadRepository.class);var service=new RagChatService(new RagBusinessService(advisor,catalog,reporting),true,"http://127.0.0.1:1");for(String q:List.of("thoi tiet hom nay","hom nay xo so ra so gi","ban co biet nau com khong")){var response=service.answer(new RagChatRequest(q,null,null,List.of(),true));assertEquals("OUT_OF_SCOPE",response.businessKind());assertFalse(response.llmUsed());assertTrue(response.businessProducts().isEmpty());}verifyNoInteractions(advisor,catalog,reporting);}
 @Test void suitabilityAndGiftsDiscloseMissingEvidenceInsteadOfInventingClaims(){
  var catalog=mock(TeaCatalogRepository.class);var product=mock(Product.class);when(product.getProductId()).thenReturn(11L);when(product.getPrice()).thenReturn(new BigDecimal("100000"));when(catalog.findVisibleCatalog()).thenReturn(List.of(product));
  var service=new RagBusinessService(mock(TeaAdvisorService.class),catalog,mock(ChatbotReadRepository.class));
  for(String question:List.of("Có phù hợp làm quà không?","Sản phẩm phù hợp với ai?","Sản phẩm này là chè gì?")){
   var result=service.snapshot(new RagChatRequest(question,null,null,List.of(),false,11L));assertEquals("PRODUCT_DETAIL",result.kind());assertTrue(result.advice().reply().contains("chưa"));assertEquals(11L,result.facts().get(0).productId());
  }
 }

}
