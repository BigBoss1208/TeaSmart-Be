package vn.teasmart.backend.service;

import java.util.*;
import java.util.regex.*;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.dto.request.TeaPreferenceRequest;

/** Catalog-owned names only; no hardcoded tea varieties or generated claims. */
final class TeaCatalogTopic {
    private TeaCatalogTopic() {}
    record Topic(String line, Long regionId) {}
    static String canonical(String value) { return TeaText.normalize(value).replaceAll("\\bche\\b","tra").replaceAll("[.,-]"," ").replaceAll(" +"," ").trim(); }
    static String core(String name) { return canonical(name).replaceFirst("^tra +",""); }
    static boolean contains(String text,String name) { return !name.isBlank() && (" "+canonical(text)+" ").contains(" "+core(name)+" "); }
    static boolean matches(Product product,String line) { return line==null || contains(product.getCategory().getName(),line) || contains(product.getName(),line); }
    static boolean knowledge(String text) {
        return text.matches("(?s).*(cach pha|pha che|pha tra|bao quan|nhiet do|ham che|ham tra|che bien|cach trong|cham soc|doi che|vung che|khi hau|dia ly|lich su|nguon goc|cong dung|suc khoe|chua benh).*");
    }
    static Topic resolve(String message,List<Product> products) {
        String text=canonical(message),line=null; Long region=null; int regionLength=0;
        for(var p:products) if(p.getRegion()!=null && core(p.getRegion().getName()).length()>regionLength && contains(text,p.getRegion().getName())) {
            region=p.getRegion().getRegionId();regionLength=core(p.getRegion().getName()).length();
        }
        int length=0;
        for(var p:products) if(p.getCategory()!=null) {
            String candidate=core(p.getCategory().getName());
            boolean regionName=products.stream().anyMatch(x->x.getRegion()!=null&&candidate.equals(core(x.getRegion().getName())));
            if(!regionName&&candidate.length()>length&&contains(text,candidate)){line=candidate;length=candidate.length();}
        }
        if(line==null&&region==null) {
            var matcher=Pattern.compile("\\btra\\s+(.+)").matcher(text);
            if(matcher.find()) {
                String candidate=matcher.group(1).split("\\b(co|khong|duoi|tren|toi|tu|va|voi|nao|nay|dang|gi|nhu|the|khac|gia|it|hau|dam|nhe|ngon|ban|hot|lam|de|cho|phu|nen|hang|moi)\\b",2)[0].trim();
                if(candidate.length()>=3&&candidate.length()<=100&&!candidate.matches(".*[0-9].*"))line=candidate;
            }
        }
        return new Topic(line,region);
    }
    static String label(TeaPreferenceRequest q,List<Product> products) {
        if(q.teaLine()!=null) return products.stream().map(Product::getCategory).filter(Objects::nonNull).filter(c->core(c.getName()).equals(core(q.teaLine()))).map(c->c.getName()).findFirst().orElse("Chè "+q.teaLine());
        if(q.regionId()!=null)return products.stream().filter(p->p.getRegion()!=null&&q.regionId().equals(p.getRegion().getRegionId())).map(p->"chè "+p.getRegion().getName()).findFirst().orElse("dòng chè bạn hỏi");
        return "dòng chè bạn hỏi";
    }
    static String safeDescription(String description) {
        if(description==null)return "";
        var sentences=description.replaceAll("<[^>]*>"," ").replaceAll("\\s+"," ").split("(?<=[.!?]) +");
        var selected=new ArrayList<String>();
        for(String sentence:sentences) {
            String normalized=TeaText.normalize(sentence);
            if(sentence.length()>240||normalized.matches("(?s).*(chung nhan|xac minh|vietgap|ocop|huu co|chua benh|dieu tri|suc khoe|tot nhat|cao nhat|https|ignore|system prompt).*"))continue;
            if(!sentence.isBlank())selected.add(sentence.trim());
            if(selected.size()==2)break;
        }
        return String.join(" ",selected);
    }
    static String introduction(TeaPreferenceRequest q,List<Product> products) {
        String description=null;
        if(q.teaLine()!=null)description=products.stream().map(Product::getCategory).filter(Objects::nonNull).filter(c->core(c.getName()).equals(core(q.teaLine()))).map(c->Objects.toString(c.getDescription(),"")).filter(s->!s.isBlank()).findFirst().orElse(null);
        else if(q.regionId()!=null)description=products.stream().filter(p->p.getRegion()!=null&&q.regionId().equals(p.getRegion().getRegionId())).map(p->Objects.toString(p.getRegion().getDescription(),"")).filter(s->!s.isBlank()).findFirst().orElse(null);
        String safe=safeDescription(description);
        return safe.isBlank()?"Mình sẽ tìm "+label(q,products)+" theo tên và phân loại trong danh mục TeaSmart; chưa đủ dữ liệu để khẳng định hương vị chung.":"Theo phần giới thiệu trong danh mục TeaSmart: "+safe;
    }
    static List<String> comparison(String message,List<Product> products) {
        var descriptions=new LinkedHashMap<String,String>();
        for(var p:products) {
            var c=p.getCategory();if(c==null||!contains(message,c.getName()))continue;
            String safe=safeDescription(c.getDescription());
            descriptions.putIfAbsent(core(c.getName()),c.getName()+": "+(safe.isBlank()?"Danh mục chưa có mô tả đủ để so sánh đặc điểm.":safe));
        }
        return descriptions.values().stream().limit(3).toList();
    }
}
