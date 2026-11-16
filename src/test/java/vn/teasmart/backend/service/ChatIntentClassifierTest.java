package vn.teasmart.backend.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static vn.teasmart.backend.service.ChatIntentClassifier.Intent.*;
class ChatIntentClassifierTest {
 @Test void bestsellerSynonyms(){for(String s:new String[]{"Sản phẩm nào bán chạy nhất?","san pham nao ban chay nhat","best seller","bestseller","chè nào hot nhất","san pham nao hot nhat","loại chè nào được mua nhiều","top 5 sản phẩm bán chạy"})assertEquals(BEST_SELLER,ChatIntentClassifier.classify(s,false),s);}
 @Test void accentsAndContext(){for(String[] pair:new String[][]{{"Sản phẩm này còn hàng không?","san pham nay con hang khong"},{"Giá bao nhiêu?","gia bao nhieu"},{"Chè này có đắng không?","che nay co dang khong"},{"Chè nào ít chát hậu ngọt?","che nao it chat hau ngot"},{"Giới thiệu đồi chè Tân Cương","gioi thieu doi che Tan Cuong"}})assertEquals(ChatIntentClassifier.classify(pair[0],true),ChatIntentClassifier.classify(pair[1],true));assertEquals(PRODUCT_PRICE,ChatIntentClassifier.classify("gia bao nhieu",true));assertEquals(PRODUCT_STOCK,ChatIntentClassifier.classify("con hang ko",true));assertEquals(PRODUCT_DETAIL,ChatIntentClassifier.classify("sản phẩm này có gì đặc biệt",true));}
 @Test void knowledgeIsNotOutsideScope(){assertEquals(TEA_ORIGIN,ChatIntentClassifier.classify("Vùng chè Tân Cương có đặc điểm khí hậu như thế nào?",false));assertEquals(BREWING_GUIDE,ChatIntentClassifier.classify("cach pha che tan cuong",false));assertEquals(TEA_KNOWLEDGE,ChatIntentClassifier.classify("bao quan che nhu the nao",false));}
 @Test void unrelatedQuestionsNeverReachKnowledge(){for(String s:new String[]{"Bây giờ là mấy giờ?","Thời tiết hôm nay?","gia vang hom nay","viet cho toi doan code Java","ket qua bong da","giải bài toán","abc xyz"})assertEquals(OUT_OF_SCOPE,ChatIntentClassifier.classify(s,true),s);}
 @Test void emptyAndGreeting(){assertEquals(UNKNOWN,ChatIntentClassifier.classify("",false));assertEquals(GREETING,ChatIntentClassifier.classify("Xin chào!",false));}
}
