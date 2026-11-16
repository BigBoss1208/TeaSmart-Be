package vn.teasmart.backend.service;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import vn.teasmart.backend.dto.request.RagChatRequest;
import vn.teasmart.backend.dto.response.RagChatResponse;
import vn.teasmart.backend.dto.response.RagChatResponse.Source;
@Service
public class RagChatService  {
    private final RagBusinessService business;
    private final boolean enabled;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json=JsonMapper.builder().build();
    public static class Failure extends RuntimeException  {
        public final String code;
        public final int status;
        public Failure(String code,int status) {
            super("Chatbot RAG chưa sẵn sàng hoặc không trả được kết quả có căn cứ.");
            this.code=code;
            this.status=status;
        }
    }
    public RagChatService(RagBusinessService business,@Value("${teasmart.rag.enabled:false}") boolean enabled,@Value("${teasmart.rag.url:http://127.0.0.1:8002}") String url) {
        this.business=business;
        this.enabled=enabled;
        URI base=URI.create(url);
        if(!Set.of("localhost","127.0.0.1","[::1]").contains(base.getHost())||!"http".equals(base.getScheme())||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null||!(base.getPath().isEmpty()||base.getPath().equals("/")))throw new IllegalArgumentException("RAG service must be loopback HTTP.");
        endpoint=base.resolve("/answer");
    }
    public RagChatResponse answer(RagChatRequest request) {
        List<String> history=request.history()==null?List.of():request.history();
        for(String text:java.util.stream.Stream.concat(java.util.stream.Stream.of(request.message()),history.stream()).toList())if(text.matches("(?is).*\\bBearer\\s+\\S+.*")||text.matches("(?s).*eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+.*"))throw new Failure("RAG_PRIVATE_INPUT",400);
        var snapshot=business.snapshot(request);
        var advice=snapshot.advice();
        // Business facts never pass through an LLM: cards and figures originate in server-owned SQL.
        if(!snapshot.kind().equals("KNOWLEDGE")) {
            String reply=switch(snapshot.kind()) {
                case "PRODUCT_SELECTION","CUSTOMER_SUPPORT","CONSULTATION","OUT_OF_SCOPE","GREETING","CLARIFICATION","PRODUCT_DETAIL"->advice.reply();
                case "BESTSELLERS"->snapshot.facts().isEmpty()?"Dạ, hiện TeaSmart chưa có đủ dữ liệu mua hàng để xác định chính xác loại chè bán chạy nhất. Mình có thể giúp bạn lựa chọn theo ngân sách hoặc khẩu vị nhé!":"Dạ, bạn muốn tham khảo những loại chè được khách hàng lựa chọn nhiều tại TeaSmart phải không ạ? 🍃 Đây là các sản phẩm bán chạy dựa trên những đơn hàng đã hoàn tất và thanh toán hợp lệ của cửa hàng.";
                case "REVIEWS"->snapshot.facts().isEmpty()?"Chưa có đánh giá APPROVED để xếp hạng.":"Xếp theo điểm trung bình từ đánh giá APPROVED; kèm số lượt đánh giá, không dùng đánh giá bị ẩn.";
                default->snapshot.facts().isEmpty()?emptyProductReply(snapshot):productReply(snapshot);
            }
            ;
            return new RagChatResponse("READ_ONLY_BUSINESS_V1",reply,advice.preferences(),advice.notices(),advice.recommendations(),List.of(),null,false,snapshot.kind(),snapshot.facts());
        }
        if(!enabled || Boolean.FALSE.equals(request.allowLlm()))return new RagChatResponse("KNOWLEDGE_UNAVAILABLE","Mình chưa có đủ tài liệu đã xác minh để trả lời câu hỏi kiến thức này lúc này. Bạn vẫn có thể hỏi mình về sản phẩm, giá, tồn kho hoặc cách mua hàng trên TeaSmart.",advice.preferences(),List.of(),new vn.teasmart.backend.dto.response.TeaRecommendationResponse("CONTENT_BASED_V1",false,List.of()),List.of(),null,false,"KNOWLEDGE",List.of());
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("LLM call cannot hold database transaction.");
        var knowledgeHistory=history.stream().filter(text -> Set.of(ChatIntentClassifier.Intent.TEA_KNOWLEDGE,ChatIntentClassifier.Intent.TEA_ORIGIN,ChatIntentClassifier.Intent.BREWING_GUIDE).contains(ChatIntentClassifier.classify(text,false))).toList();
        var body=Map.of("question",request.message(),"history",knowledgeHistory);
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=http.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(35)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),info->new LimitedBody());
            var response=pending.get(35,TimeUnit.SECONDS);
            byte[] bytes=response.body();
            if(response.statusCode()!=200) {
                String code="RAG_UNAVAILABLE";
                try {
                    String c=json.readTree(bytes).path("code").asText();
                    if(Set.of("RAG_NOT_CONFIGURED","RAG_PROVIDER_UNAVAILABLE","RAG_TIMEOUT","RAG_INVALID_OUTPUT","RAG_UNGROUNDED_OUTPUT","RAG_BUSINESS_CLAIM_REJECTED","RAG_BUSY","RAG_CALLS_DISABLED","RAG_RATE_LIMITED","RAG_PROVIDER_AUTH_REJECTED","RAG_MODEL_UNAVAILABLE","RAG_MODEL_RETIRED","RAG_PROVIDER_CONFIG_INVALID").contains(c))code=c;
                }
                catch(RuntimeException ignored) {
                }
                throw new Failure(code,response.statusCode()==504?504:503);
            }
            JsonNode node=json.readTree(bytes);
            String reply=node.path("reply").asText(),method=node.path("method").asText();
            if(reply.isBlank()||reply.length()>4000||!Set.of("RAG_LLM_V1","RAG_NO_EVIDENCE").contains(method)||!node.path("sources").isArray())throw new Failure("RAG_INVALID_OUTPUT",502);
            var sources=new ArrayList<Source>();
            for(var source:node.get("sources")) {
                String url=source.path("url").asText();
                URI uri=URI.create(url);
                if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new Failure("RAG_INVALID_OUTPUT",502);
                for(String field:List.of("id","title","publisher","accessedAt"))if(source.path(field).asText().isBlank())throw new Failure("RAG_INVALID_OUTPUT",502);
                sources.add(new Source(source.path("id").asText(),source.path("title").asText(),url,source.path("publisher").asText(),source.path("accessedAt").asText()));
                if(sources.size()>4)throw new Failure("RAG_INVALID_OUTPUT",502);
            }
            if((method.equals("RAG_LLM_V1")&&(sources.isEmpty()||!node.path("llmUsed").asBoolean()))||(method.equals("RAG_NO_EVIDENCE")&&!sources.isEmpty()))throw new Failure("RAG_INVALID_OUTPUT",502);
            return new RagChatResponse(method,reply,advice.preferences(),advice.notices(),advice.recommendations(),List.copyOf(sources),node.path("embeddingMethod").asText(),node.path("llmUsed").asBoolean(),"KNOWLEDGE",List.of());
        }
        catch(Failure e) {
            throw e;
        }
        catch(TimeoutException e) {
            if(pending!=null)pending.cancel(true);
            throw new Failure("RAG_TIMEOUT",504);
        }
        catch(ExecutionException e) {
            if(e.getCause() instanceof Failure failure)throw failure;
            if(e.getCause() instanceof HttpTimeoutException)throw new Failure("RAG_TIMEOUT",504);
            throw new Failure("RAG_UNAVAILABLE",503);
        }
        catch(InterruptedException e) {
            if(pending!=null)pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure("RAG_UNAVAILABLE",503);
        }
        catch(Exception e) {
            throw new Failure("RAG_UNAVAILABLE",503);
        }
    }
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]>  {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() {
            return result;
        }
        public void onSubscribe(Flow.Subscription s) {
            subscription=s;
            s.request(1);
        }
        public void onNext(List<ByteBuffer> chunks) {
            for(var chunk:chunks) {
                if(buffer.size()+chunk.remaining()>32768) {
                    subscription.cancel();
                    result.completeExceptionally(new Failure("RAG_INVALID_OUTPUT",502));
                    return;
                }
                byte[] bytes=new byte[chunk.remaining()];
                chunk.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }
    }
    private static String emptyProductReply(RagBusinessService.Snapshot snapshot) {
        if(Integer.valueOf(1).equals(snapshot.advice().preferences().astringency())) {
            return "Dạ, mình chưa tìm thấy loại chè có thông tin độ chát thấp đã xác nhận và phù hợp ngân sách của bạn. Mình có thể giúp bạn xem các lựa chọn khác hoặc điều chỉnh mức giá nhé!";
        }
        return "Dạ, hiện chưa có sản phẩm còn hàng phù hợp với những tiêu chí bạn vừa chọn. Bạn muốn điều chỉnh ngân sách hoặc khẩu vị để mình tìm tiếp không ạ?";
    }
    private static String productReply(RagBusinessService.Snapshot snapshot) {
        var q=snapshot.advice().preferences();
        var format=java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("vi-VN"));
        boolean exclusive=q.maxPrice()!=null&&q.maxPrice().remainder(java.math.BigDecimal.ONE).compareTo(new java.math.BigDecimal("0.99"))==0;
        String budget=q.maxPrice()==null?"":" trong ngân sách "+(q.minPrice()==null?(exclusive?"dưới ":"tối đa "):"từ "+format.format(q.minPrice())+"đ đến ")+format.format(exclusive?q.maxPrice().add(new java.math.BigDecimal("0.01")):q.maxPrice())+"đ";
        String reply="Dạ, TeaSmart có một số loại chè"+budget+" để bạn dễ lựa chọn nhé! 🍃";
        if(snapshot.facts().size()>4)reply="Dạ, TeaSmart hiện có "+snapshot.facts().size()+" sản phẩm"+budget+". Mình giới thiệu trước một vài lựa chọn để bạn tham khảo nhé! 🍃";
        if(Integer.valueOf(1).equals(q.astringency()))reply="Dạ, mình sẽ ưu tiên các loại chè có độ chát thấp"+budget+" cho bạn nhé! 🌿 Mỗi lựa chọn bên dưới đều có thông tin giá và độ chát để bạn dễ so sánh.";
        if("GIFT".equals(q.purpose()))reply+=" Đây là gợi ý theo dữ liệu danh mục; thông tin bao bì quà biếu cần được cửa hàng xác nhận.";
        if(q.strength()==null&&q.astringency()==null)reply+=" Bạn thích chè đậm truyền thống hay ít chát, dễ uống hơn ạ?";
        if(snapshot.advice().notices().stream().anyMatch(n->n.contains("±10%")))reply+=" Mình hiểu khoảng ngân sách là ±10%; bạn có thể cho khoảng giá chính xác hơn.";
        if(snapshot.advice().recommendations().items().stream().flatMap(i->i.reasons().stream()).anyMatch(r->r.startsWith("Chưa có dữ liệu:")))reply+=" Một số sản phẩm chưa có đủ thông tin khẩu vị; mình không khẳng định chúng đáp ứng đặc điểm chưa được ghi nhận.";
        return reply;
    }
}
