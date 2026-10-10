package vn.teasmart.backend.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.dto.request.TeaPreferenceRequest;

/** Small Vietnamese intent grammar; no LLM, external prompt, user history or invented confidence. */
public final class TeaIntentParser {
    private TeaIntentParser() {}
    private static final Pattern MONEY=Pattern.compile("(?<![a-z0-9.,-])([0-9]{1,10}(?:[.,][0-9]{1,3})*)\\s*(trieu|nghin|ngan|tr|k|vnd|dong|d)(?![a-z])");
    public record Parsed(TeaPreferenceRequest preferences,List<String> notices) {}
    public static Parsed parse(String message,TeaPreferenceRequest supplied,List<Product> catalog) {
        var explicit=supplied==null?TeaPreferenceRequest.empty():supplied;
        String text=TeaText.normalize(message);
        List<String> notices=new ArrayList<>();
        if(text.matches(".*-\\s*[0-9].*"))notices.add("Không suy đoán ngân sách âm; hãy nhập giá bằng bộ lọc.");
        BigDecimal min=null,max=null; List<BigDecimal> amounts=new ArrayList<>();
        var matcher=MONEY.matcher(text);
        while(matcher.find() && amounts.size()<3) {
            String unit=matcher.group(2),number=matcher.group(1);
            try {
                boolean fraction=Set.of("trieu","tr","k","nghin","ngan").contains(unit);
                BigDecimal value=new BigDecimal(fraction?number.replace(',','.'):number.replace(".","").replace(",",""));
                value=value.multiply(unit.equals("trieu")||unit.equals("tr")?new BigDecimal("1000000"):
                        Set.of("k","nghin","ngan").contains(unit)?new BigDecimal("1000"):BigDecimal.ONE);
                if(value.signum()>0 && value.compareTo(new BigDecimal("9999999999.99"))<=0 && value.stripTrailingZeros().scale()<=2) amounts.add(value);
            } catch(NumberFormatException ignored) { /* Ambiguous grouping is not guessed. */ }
        }
        if(amounts.size()==2 && (text.contains(" den ")||text.contains(" toi ")||text.contains(" - "))) {
            min=amounts.get(0);max=amounts.get(1);
        } else if(amounts.size()==1) {
            if(text.contains("tren ")||text.contains("tu ")&&!text.contains("toi da")) min=amounts.get(0);
            else max=amounts.get(0);
        } else if(!amounts.isEmpty()) notices.add("Ngân sách chưa rõ; hãy dùng ô giá tối thiểu/tối đa.");
        if(min!=null&&max!=null&&min.compareTo(max)>0) { min=null;max=null;notices.add("Khoảng giá trong tin nhắn chưa hợp lệ; hãy nhập ngân sách bằng bộ lọc."); }
        Long category=null,region=null; int categoryLength=0,regionLength=0;
        for(Product p:catalog) {
            String c=TeaText.normalize(p.getCategory().getName()),r=TeaText.normalize(p.getRegion().getName());
            if(c.length()>categoryLength&&containsName(text,c)) {category=p.getCategory().getCategoryId();categoryLength=c.length();}
            if(r.length()>regionLength&&containsName(text,r)) {region=p.getRegion().getRegionId();regionLength=r.length();}
        }
        Integer strength=null,astringency=null,aroma=null,aftertaste=null;
        if(text.contains("dam vi")||text.contains("dam da")||text.contains("vi dam"))strength=4;
        if(text.contains("nhe vi")||text.contains("vi nhe")||text.contains("khong dam")||text.matches(".*khong (thich |muon )?dam vi.*"))strength=2;
        if(text.contains("it chat")||text.contains("khong chat")||text.contains("chat nhe"))astringency=1;
        else if(text.contains("chat dam")||text.contains("vi chat"))astringency=4;
        if(text.contains("thom")||text.contains("huong manh"))aroma=4;
        if(text.contains("hau ngot"))aftertaste=4;
        if(text.contains("khong thom")||text.contains("it thom"))aroma=1;
        if(text.contains("khong hau ngot"))aftertaste=1;
        String purpose=text.contains("lam qua")||text.contains("qua tang")?"GIFT":
                text.contains("hang ngay")||text.contains("moi ngay")?"DAILY":null;
        if(text.contains("chua benh")||text.contains("dieu tri"))notices.add("Trợ lý không tư vấn điều trị bệnh hoặc cam kết tác dụng sức khỏe.");
        var result=new TeaPreferenceRequest(choose(explicit.categoryId(),category),choose(explicit.regionId(),region),
                choose(explicit.minPrice(),min),choose(explicit.maxPrice(),max),choose(explicit.strength(),strength),
                choose(explicit.astringency(),astringency),choose(explicit.aroma(),aroma),choose(explicit.aftertaste(),aftertaste),choose(explicit.purpose(),purpose));
        notices.add("Ngân sách tính theo một gói sản phẩm. Mức vị 1–5 dùng để xếp hạng tương đối, không bảo đảm cảm nhận thực tế.");
        return new Parsed(result,List.copyOf(notices));
    }
    private static boolean containsName(String text,String name) {return !name.isBlank()&&(" "+text.replaceAll("[.,-]"," ").replaceAll(" +"," ")+" ").contains(" "+name+" ");}
    private static <T> T choose(T explicit,T inferred) {return explicit==null?inferred:explicit;}
}
