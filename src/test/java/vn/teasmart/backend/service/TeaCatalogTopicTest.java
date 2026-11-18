package vn.teasmart.backend.service;
import java.util.*;import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import vn.teasmart.backend.entity.*;import vn.teasmart.backend.repository.*;import vn.teasmart.backend.dto.request.*;import vn.teasmart.backend.dto.response.*;
class TeaCatalogTopicTest {
 private Product product(long id,String name,String category,long categoryId,String region,long regionId,int price,int stock,int bitter){
  var p=new Product();p.setProductId(id);p.setName(name);p.setSlug("fixture-"+id);p.setPrice(BigDecimal.valueOf(price));p.setStockQuantity((long)stock);p.setWeightGrams(100L);p.setAstringencyLevel(bitter);
  var c=new Category();c.setCategoryId(categoryId);c.setName(category);c.setDescription(category+" được mô tả trong danh mục kiểm thử. Đặc điểm cụ thể xem từng sản phẩm.");p.setCategory(c);
  var r=new TeaRegion();r.setRegionId(regionId);r.setName(region);r.setDescription(region+" là vùng chè trong danh mục kiểm thử. Thông tin từng sản phẩm được ghi riêng.");p.setRegion(r);return p;
 }
 private List<Product> products(){return List.of(product(1,"Trà Đinh Tân Cương","Trà Đinh",11,"Tân Cương",21,450000,8,1),product(2,"Trà Nõn Tôm Tân Cương","Trà Nõn Tôm",12,"Tân Cương",21,180000,3,1),product(3,"Trà Móc Câu Tân Cương","Trà Móc Câu",13,"Tân Cương",21,250000,2,2),product(4,"Trà Nõn Tôm La Bằng","Trà Nõn Tôm",12,"La Bằng",22,220000,3,4),product(5,"Trà Móc Câu Phúc Xuân","Trà Móc Câu",13,"Phúc Xuân",23,165000,4,2),product(6,"Trà Búp Non Khe Cốc","Trà Búp Non",14,"Khe Cốc",24,130000,5,1),product(7,"Hộp Trà Nõn Tôm","Trà Quà Tặng",15,"Tân Cương",21,200000,1,2),product(8,"Trà Đinh Hết Hàng","Trà Đinh",11,"Tân Cương",21,250000,0,1),product(9,"Trà Bạch Trà","Trà Bạch Trà",16,"Khe Cốc",24,600000,2,3));}
 private RagBusinessService service(){var catalog=mock(TeaCatalogRepository.class);when(catalog.findVisibleCatalog()).thenReturn(products());return new RagBusinessService(new TeaAdvisorService(catalog),catalog,mock(ChatbotReadRepository.class),mock(vn.teasmart.backend.service.ProductService.class));}
 private RagBusinessService.Snapshot ask(String q,TeaPreferenceRequest context){return service().snapshot(new RagChatRequest(q,null,context,List.of(),false));}
 private Set<Long> ids(RagBusinessService.Snapshot s){return new HashSet<>(s.facts().stream().map(RagChatResponse.BusinessProduct::productId).toList());}
 @Test void namedLinesFilterRealMetadataIncludingGiftNames(){
  var cases=Map.of("Chè Tân Cương",Set.of(1L,2L,3L,7L),"Chè đinh",Set.of(1L),"Chè nõn tôm",Set.of(2L,4L,7L),"Chè móc câu",Set.of(3L,5L),"Chè La Bằng",Set.of(4L),"Chè bạch trà",Set.of(9L));
  cases.forEach((q,expected)->{var s=ask(q,null);assertEquals("LINE_PRODUCTS",s.kind(),q);assertEquals(expected,ids(s),q);assertTrue(s.advice().reply().contains(expected.size()+" sản phẩm"));assertTrue(s.advice().reply().contains("danh mục"));assertTrue(s.advice().reply().contains("uống hằng ngày hay làm quà"));});
 }
 @Test void introAliasesAndAvailabilityDoNotDumpCatalog(){for(String q:List.of("Giới thiệu chè đinh","che dinh","Chè nõn tôm có gì hay?","Có chè nõn tôm không?")){var s=ask(q,null);assertEquals("LINE_PRODUCTS",s.kind(),q);assertEquals(q.contains("đinh")||q.equals("che dinh")?Set.of(1L):Set.of(2L,4L,7L),ids(s));}}
 @Test void regionAndStrictBudgetApplyTogether(){var s=ask("Chè Tân Cương dưới 300k",null);assertEquals(Set.of(2L,3L,7L),ids(s));assertTrue(s.facts().stream().allMatch(p->p.price().compareTo(BigDecimal.valueOf(300000))<0));}
 @Test void followupsKeepLineAndBudgetThenExplicitNewTopicReplacesThem(){
  var initial=ask("Chè Tân Cương",null);var budget=ask("Loại nào dưới 300k?",initial.advice().preferences());var taste=ask("Loại nào ít chát?",budget.advice().preferences());assertEquals(Set.of(2L,3L,7L),ids(taste));assertEquals(21L,taste.advice().preferences().regionId());
  var type=ask("Chè nõn tôm",null);var follow=ask("Loại nào dưới 300k?",type.advice().preferences());assertEquals("non tom",follow.advice().preferences().teaLine());assertEquals(Set.of(2L,4L,7L),ids(follow));
  var changed=ask("Chè La Bằng dưới 300k",taste.advice().preferences());assertEquals(Set.of(4L),ids(changed));assertEquals(22L,changed.advice().preferences().regionId());
 }
 @Test void genericBudgetDoesNotInferALine(){var s=ask("Chè dưới 300k",null);assertEquals("PRODUCTS",s.kind());assertNull(s.advice().preferences().teaLine());assertNull(s.advice().preferences().regionId());assertEquals(Set.of(2L,3L,4L,5L,6L,7L),ids(s));}
 @Test void comparisonUsesCatalogDescriptionsWithoutCardsOrProvider(){var s=ask("Chè đinh và chè nõn tôm khác nhau gì?",null);assertEquals("PRODUCT_DETAIL",s.kind());assertTrue(s.facts().isEmpty());assertTrue(s.advice().reply().contains("Trà Đinh:"));assertTrue(s.advice().reply().contains("Trà Nõn Tôm:"));}
 @Test void brewingKeepsKnowledgeRouteAndUnavailableProviderRemainsHonest(){var s=ask("Cách pha chè Tân Cương?",null);assertEquals("KNOWLEDGE",s.kind());assertTrue(s.facts().isEmpty());var r=new RagChatService(service(),false,"http://127.0.0.1:1").answer(new RagChatRequest("Cách pha chè Tân Cương?",null,null,List.of(),false));assertEquals("KNOWLEDGE_UNAVAILABLE",r.method());assertFalse(r.llmUsed());}
 @Test void unknownLineDoesNotRelaxToAllProducts(){var s=ask("Có chè ô long không?",null);assertEquals("LINE_PRODUCTS",s.kind());assertTrue(s.facts().isEmpty());assertTrue(s.advice().reply().contains("chưa có sản phẩm còn hàng"));}
 @Test void unsafeDescriptionCannotCreateMedicalOrCertificationClaims(){var safe=TeaCatalogTopic.safeDescription("Chè chữa bệnh hiệu quả. Có chứng nhận OCOP. Mô tả hình dáng cánh trà trong catalog.");assertEquals("Mô tả hình dáng cánh trà trong catalog.",safe);}
 @Test void explicitDifferentTeaLineStillUsesCatalogOnProductPage() {
  var result=service().snapshot(new RagChatRequest("Chè La Bằng",null,null,List.of(),false,99L));
  assertEquals("LINE_PRODUCTS",result.kind());
  assertFalse(result.facts().isEmpty());
  assertEquals("KNOWLEDGE",service().snapshot(new RagChatRequest("Cách pha chè Tân Cương?",null,null,List.of(),false,99L)).kind());
 }

 @Test void knowledgeFollowupsAndCatalogSwitchKeepOnlyRelevantContext() {
  var business=service();
  var history=new ArrayList<String>();
  var session="67e93452-8bbc-4541-9895-e0556b642fb2";
  TeaPreferenceRequest oldCatalog=ask("Chè La Bằng",null).advice().preferences();
  var serverCalls=new java.util.concurrent.CopyOnWriteArrayList<String>();
  com.sun.net.httpserver.HttpServer server;
  try {
   server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  } catch (java.io.IOException e) { throw new AssertionError(e); }
  server.createContext("/answer", exchange -> {
   String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
   serverCalls.add(tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("question").asText());
   byte[] bytes="""
     {"method":"RAG_LLM_V1","reply":"Fixture", "sources":[{"id":"fixture:0","title":"Nguồn kiểm thử","url":"https://example.invalid","publisher":"Fixture","accessedAt":"2026-11-18"}],
      "llmUsed":true,"evidenceStatus":"CITED_EVIDENCE"}
     """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(200,bytes.length);
   try(var response=exchange.getResponseBody()){response.write(bytes);}
  });
  server.start();
  try {
   var chat=new RagChatService(business,true,"http://127.0.0.1:"+server.getAddress().getPort());
   for(String question:List.of("Cách pha chè Tân Cương như thế nào?", "Pha lần hai thì sao?",
           "Loại chè này bảo quản thế nào?", "Vì sao vậy?")) {
    var response=chat.answer(new RagChatRequest(question,null,oldCatalog,List.copyOf(history),true,123L,List.of(4L),session),"fixture");
    assertEquals("CITED_EVIDENCE",response.evidenceStatus(),question);
    assertTrue(response.businessProducts().isEmpty());
    history.add(question);
   }
   assertEquals(List.of("Cách pha chè Tân Cương như thế nào?", "Khi pha chè Tân Cương, pha nước thứ hai thế nào?",
           "Cách bảo quản chè Tân Cương sau khi mở bao bì như thế nào?",
           "Vì sao cần bảo quản chè Tân Cương đúng cách sau khi mở bao bì?"),serverCalls);

   var shoppingRoute=TeaKnowledgeConversation.resolve("TeaSmart có bán loại này không?",history,false);
   assertNotNull(shoppingRoute);
   assertEquals(TeaKnowledgeConversation.Action.CATALOG,shoppingRoute.action());
   assertEquals("chè Tân Cương",shoppingRoute.question());
   var offer=chat.answer(new RagChatRequest("TeaSmart có bán loại này không?",null,oldCatalog,history,true,123L,List.of(4L),session),"fixture");
   assertEquals("LINE_PRODUCTS",offer.businessKind());
   assertEquals(21L,offer.preferences().regionId());
   assertTrue(offer.businessProducts().stream().noneMatch(p->p.productId()==4L));
   var budget=chat.answer(new RagChatRequest("Loại nào dưới 300k?",null,offer.preferences(),history,true),"fixture");
   assertEquals(Set.of(2L,3L,7L),new HashSet<>(budget.businessProducts().stream().map(RagChatResponse.BusinessProduct::productId).toList()));
   assertEquals(4,serverCalls.size(),"Shopping does not dispatch RAG");

   var switchBack=chat.answer(new RagChatRequest("Cách pha chè La Bằng?",null,budget.preferences(),history,true),"fixture");
   assertEquals("CITED_EVIDENCE",switchBack.evidenceStatus());
   assertEquals("Cách pha chè La Bằng như thế nào?",serverCalls.get(4));
  } finally {
   server.stop(0);
  }
 }

 @Test void ambiguousFollowupsAskForClarificationWithoutCallingPython() {
  var chat=new RagChatService(service(),true,"http://127.0.0.1:1");
  for(String question:List.of("Pha lần hai thì sao?","Thế còn lần hai?","Vì sao vậy?","Loại chè này bảo quản thế nào?","TeaSmart có bán loại này không?")) {
   var result=chat.answer(new RagChatRequest(question,null,null,List.of(),true));
   assertEquals("CLARIFICATION",result.businessKind(),question);
   assertFalse(result.llmUsed());
  }
  var afterSwitch=chat.answer(new RagChatRequest("Vì sao vậy?",null,null,List.of("Cách pha chè Tân Cương?","chè dưới 200k"),true));
  assertEquals("CLARIFICATION",afterSwitch.businessKind());
 }

 @Test void explicitKnowledgeUsesFocusedQuestionsWithoutProductCards() {
  var business=service();
  var staleProductFilters=ask("Chè Tân Cương dưới 300k",null).advice().preferences();
  var scenarios=Map.of(
      "Chè La Bằng có gì đặc biệt?", "Điểm đặc biệt của chè La Bằng là gì?",
      "Tôi muốn tìm hiểu về chè La Bằng", "Vùng chè, nét đặc trưng và văn hóa của chè La Bằng có gì đáng chú ý?",
      "Chè La Bằng và chè Tân Cương khác nhau như thế nào?", "chè La Bằng và chè Tân Cương khác nhau thế nào?",
      "Chè La Bằng và Tân Cương khác nhau thế nào?", "chè La Bằng và chè Tân Cương khác nhau thế nào?",
      "Chè Tân Cương có ngon không?", "chè Tân Cương có hương vị như thế nào? Ngon hay không có tùy khẩu vị không?",
      "Vì sao phải bảo quản chè kín?", "Vì sao nên bảo quản chè trong bao bì kín?");
  scenarios.forEach((question, expected) -> {
   for(Long productId : Arrays.asList(null, 99L)) {
    var answer=business.snapshot(new RagChatRequest(question,null,staleProductFilters,List.of("Chè Tân Cương dưới 300k"),true,productId,List.of(2L)));
    assertEquals("KNOWLEDGE",answer.kind(),question);
    assertEquals(expected,answer.knowledgeQuestion());
    assertTrue(answer.facts().isEmpty());
    assertTrue(answer.advice().recommendations().items().isEmpty());
   }
  });
 }

 @Test void regionComparisonFollowupRetainsBothSubjectsAndDoesNotGuessWhatToBuy() {
  var business=service();
  var history=List.of("Chè La Bằng và chè Tân Cương khác nhau thế nào?");
  var comparison=business.snapshot(new RagChatRequest("Hai loại này khác nhau thế nào?",null,null,history,true,99L));
  assertEquals("KNOWLEDGE",comparison.kind());
  assertEquals("chè La Bằng và chè Tân Cương khác nhau thế nào?",comparison.knowledgeQuestion());
  var shopping=business.snapshot(new RagChatRequest("TeaSmart có bán loại này không?",null,null,history,true));
  assertEquals("CLARIFICATION",shopping.kind());
 }

 @Test void explicitAvailabilityStillUsesCatalogEvenWithPreviousKnowledge() {
  var business=service();
  var history=List.of("Chè La Bằng có gì đặc biệt?");
  for(String question:List.of("TeaSmart bán chè Tân Cương nào?", "TeaSmart có bán chè Tân Cương không?")) {
   var offer=business.snapshot(new RagChatRequest(question,null,null,history,true));
   assertEquals("LINE_PRODUCTS",offer.kind(),question);
   assertEquals(Set.of(1L,2L,3L,7L),ids(offer));
  }
 }

 @Test void failedKnowledgeTurnsStillAnchorTheNextQuestion() throws Exception {
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  var queries=new java.util.concurrent.CopyOnWriteArrayList<String>();
  var json=tools.jackson.databind.json.JsonMapper.builder().build();
  server.createContext("/answer",exchange -> {
   String query=json.readTree(exchange.getRequestBody().readAllBytes()).path("question").asText();
   queries.add(query);
   int status;
   String response;
   if(query.startsWith("Cách pha")) {
    status=503;
    response="{\"code\":\"RAG_PROVIDER_UNAVAILABLE\"}";
   } else if(query.startsWith("Cách bảo quản")) {
    status=429;
    response="{\"code\":\"RAG_DEMO_LIMIT_REACHED\"}";
   } else {
    status=200;
    response="""
      {"method":"RAG_LLM_V1","reply":"Fixture","sources":[{"id":"fixture:0","title":"Fixture","url":"https://example.invalid","publisher":"Fixture","accessedAt":"2026-11-18"}],
       "llmUsed":true,"evidenceStatus":"CITED_EVIDENCE"}
      """;
   }
   byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.sendResponseHeaders(status,bytes.length);
   try(var body=exchange.getResponseBody()){body.write(bytes);}
  });
  server.start();
  try {
   var chat=new RagChatService(service(),true,"http://127.0.0.1:"+server.getAddress().getPort());
   String brewing="Cách pha chè Tân Cương như thế nào?";
   String second="Pha lần hai thì sao?";
   String storage="Loại chè này bảo quản thế nào?";
   var unavailable=assertThrows(RagChatService.Failure.class,()->chat.answer(new RagChatRequest(brewing,null,null,List.of(),true)));
   assertEquals("RAG_PROVIDER_UNAVAILABLE",unavailable.code);
   assertEquals("CITED_EVIDENCE",chat.answer(new RagChatRequest(second,null,null,List.of(brewing),true)).evidenceStatus());
   var limited=assertThrows(RagChatService.Failure.class,()->chat.answer(new RagChatRequest(storage,null,null,List.of(brewing,second),true)));
   assertEquals(429,limited.status);
   assertEquals("RAG_DEMO_LIMIT_REACHED",limited.code);
   var reason=chat.answer(new RagChatRequest("Vì sao cần bảo quản như vậy?",null,null,List.of(brewing,second,storage),true));
   assertEquals("CITED_EVIDENCE",reason.evidenceStatus());
   assertTrue(reason.businessProducts().isEmpty());
   assertEquals(List.of(brewing,"Khi pha chè Tân Cương, pha nước thứ hai thế nào?",
       "Cách bảo quản chè Tân Cương sau khi mở bao bì như thế nào?",
       "Vì sao cần bảo quản chè Tân Cương đúng cách sau khi mở bao bì?"),queries);
  } finally {
   server.stop(0);
  }
 }
}
