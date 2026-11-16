package vn.teasmart.backend.chatbot;
import java.util.*;import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.service.*;import vn.teasmart.backend.dto.request.*;import vn.teasmart.backend.dto.response.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class RagChatServiceTest {
 private TeaAdviceResponse advice(){return new TeaAdviceResponse("RULE_BASED_CATALOG_V1","fixture",TeaPreferenceRequest.empty(),List.of(),new TeaRecommendationResponse("CONTENT_BASED_V1",true,List.of()));}
 @Test void disabledKnowledgeFailsClosedAfterReadOnlyClassification(){var business=mock(RagBusinessService.class);var request=new RagChatRequest("cách pha chè",null,null,List.of());when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),"KNOWLEDGE",List.of()));var result=new RagChatService(business,false,"http://127.0.0.1:1").answer(request);assertEquals("KNOWLEDGE_UNAVAILABLE",result.method());assertFalse(result.llmUsed());assertTrue(result.sources().isEmpty());}
 @Test void catalogWorksWithoutRagOrPython(){var business=mock(RagBusinessService.class);var request=new RagChatRequest("chè dưới 300k",null,null,List.of(),false);var product=new RagChatResponse.BusinessProduct(1L,"Fixture","fixture",new BigDecimal("100000"),7L,null,null,null);when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),"PRODUCTS",List.of(product)));var result=new RagChatService(business,false,"http://127.0.0.1:1").answer(request);assertFalse(result.llmUsed());assertEquals(product,result.businessProducts().get(0));assertTrue(result.sources().isEmpty());}
 @Test void unavailablePythonFailsWithoutInventingKnowledge(){var business=mock(RagBusinessService.class);var request=new RagChatRequest("cách pha chè",null,null,List.of(),true);when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),"KNOWLEDGE",List.of()));var error=assertThrows(RagChatService.Failure.class,()->new RagChatService(business,true,"http://127.0.0.1:1").answer(request));assertEquals("RAG_UNAVAILABLE",error.code);}
 @Test void rejectsNonLoopbackTargets(){for(String url:List.of("http://example.invalid","https://127.0.0.1","http://user@127.0.0.1","http://127.0.0.1/path"))assertThrows(IllegalArgumentException.class,()->new RagChatService(mock(RagBusinessService.class),true,url));}
 @Test void bearerInHistoryNeverReachesDatabaseOrAi(){var business=mock(RagBusinessService.class);var service=new RagChatService(business,true,"http://127.0.0.1:8002");assertThrows(RagChatService.Failure.class,()->service.answer(new RagChatRequest("chè",null,null,List.of("Bearer fixture-token"))));verifyNoInteractions(business);}
 @Test void businessFactsComeFromRepositorySnapshotNotLlm(){var business=mock(RagBusinessService.class);var request=new RagChatRequest("top 5",null,null,List.of());var product=new RagChatResponse.BusinessProduct(1L,"Fixture","fixture",new BigDecimal("123.45"),7L,3L,null,null);when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),"BESTSELLERS",List.of(product)));var result=new RagChatService(business,true,"http://127.0.0.1:1").answer(request);assertFalse(result.llmUsed());assertEquals(3L,result.businessProducts().get(0).unitsSold());assertEquals("READ_ONLY_BUSINESS_V1",result.method());}
 @Test void emptySalesNeverInventRanking(){var business=mock(RagBusinessService.class);var request=new RagChatRequest("bán chạy",null,null,List.of());when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),"BESTSELLERS",List.of()));var result=new RagChatService(business,true,"http://127.0.0.1:1").answer(request);assertTrue(result.reply().contains("Chưa có dữ liệu"));assertTrue(result.businessProducts().isEmpty());}
 private RagChatService withPythonResponse(String response,int status,java.util.function.Consumer<RagChatService> assertion)throws Exception{
  var business=mock(RagBusinessService.class);var request=new RagChatRequest("cách pha trà",null,null,List.of("tôi thích chè nhẹ"));when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),"KNOWLEDGE",List.of()));
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/answer",exchange->{var body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(body.contains("question"));assertTrue(body.contains("history"));assertFalse(body.contains("Authorization"));assertFalse(body.contains("password"));byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
  try{var service=new RagChatService(business,true,"http://127.0.0.1:"+server.getAddress().getPort());assertion.accept(service);return service;}finally{server.stop(0);}
 }
 @Test void forwardsKnowledgeAndValidatedSourcesFromPythonTestDouble()throws Exception{
  withPythonResponse("{\"method\":\"RAG_LLM_V1\",\"reply\":\"Fixture answer\",\"sources\":[{\"id\":\"fixture:0\",\"title\":\"Fixture source\",\"url\":\"https://example.invalid/source\",\"publisher\":\"Fixture\",\"accessedAt\":\"2026-10-17\"}],\"embeddingMethod\":\"TEST_DOUBLE\",\"llmUsed\":true}",200,service->{var result=service.answer(new RagChatRequest("cách pha trà",null,null,List.of("tôi thích chè nhẹ")));assertEquals("Fixture answer",result.reply());assertEquals(1,result.sources().size());assertEquals("KNOWLEDGE",result.businessKind());});
 }
 @Test void pythonUnavailableDoesNotInventAnAnswer()throws Exception{
  withPythonResponse("{\"code\":\"RAG_NOT_CONFIGURED\"}",503,service->{var error=assertThrows(RagChatService.Failure.class,()->service.answer(new RagChatRequest("cách pha trà",null,null,List.of("tôi thích chè nhẹ"))));assertEquals("RAG_NOT_CONFIGURED",error.code);assertEquals(503,error.status);});
 }
 @Test void oversizedPythonBodyIsRejected()throws Exception{
  withPythonResponse("x".repeat(32769),200,service->{var error=assertThrows(RagChatService.Failure.class,()->service.answer(new RagChatRequest("cách pha trà",null,null,List.of("tôi thích chè nhẹ"))));assertEquals("RAG_INVALID_OUTPUT",error.code);});
 }
 @Test void widgetCanBlockPaidLlmBeforeAnyHttpCall(){var business=mock(RagBusinessService.class);var request=new RagChatRequest("cách pha trà",null,null,List.of(),false);when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),"KNOWLEDGE",List.of()));var result=new RagChatService(business,true,"http://127.0.0.1:1").answer(request);assertEquals("KNOWLEDGE_UNAVAILABLE",result.method());assertFalse(result.llmUsed());}
 @Test void automaticRequestsUseIntentWithoutClientMode(){
  var business=mock(RagBusinessService.class);var service=new RagChatService(business,true,"http://127.0.0.1:1");
  for(String kind:List.of("PRODUCTS","CONSULTATION","CUSTOMER_SUPPORT","BESTSELLERS","OUT_OF_SCOPE","PRODUCT_DETAIL")){
   var request=new RagChatRequest(kind,null,null,List.of());when(business.snapshot(request)).thenReturn(new RagBusinessService.Snapshot(advice(),kind,List.of()));
   var result=service.answer(request);assertEquals(kind,result.businessKind());assertFalse(result.llmUsed());
  }
 }
}
