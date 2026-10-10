package vn.teasmart.backend.service;

import java.math.*;
import java.util.*;
import vn.teasmart.backend.entity.Product;
import vn.teasmart.backend.dto.request.TeaPreferenceRequest;
import vn.teasmart.backend.dto.response.*;

/** Weighted, explainable content similarity. Scores are ranking values, not probabilities. */
public final class TeaRecommendationEngine {
    private TeaRecommendationEngine() {}
    public static TeaRecommendationResponse rank(List<Product> catalog,Product seed,TeaPreferenceRequest q,int limit) {
        return rank(catalog,seed,q,limit,null);
    }
    public static TeaRecommendationResponse rank(List<Product> catalog,Product seed,TeaPreferenceRequest q,int limit,String message) {
        List<TeaRecommendationResponse.Item> items=new ArrayList<>();
        for(Product p:catalog) {
            if(seed!=null&&p.getProductId().equals(seed.getProductId())||p.getStockQuantity()<=0)continue;
            if(q.categoryId()!=null&&!q.categoryId().equals(p.getCategory().getCategoryId()))continue;
            if(q.regionId()!=null&&!q.regionId().equals(p.getRegion().getRegionId()))continue;
            if(q.minPrice()!=null&&p.getPrice().compareTo(q.minPrice())<0||q.maxPrice()!=null&&p.getPrice().compareTo(q.maxPrice())>0)continue;
            double sum=0,weight=0; List<String> reasons=new ArrayList<>();
            Long category=q.categoryId()!=null?q.categoryId():seed==null?null:seed.getCategory().getCategoryId();
            Long region=q.regionId()!=null?q.regionId():seed==null?null:seed.getRegion().getRegionId();
            if(category!=null) {weight+=.25;if(category.equals(p.getCategory().getCategoryId())) {sum+=.25;reasons.add("Cùng danh mục: "+p.getCategory().getName());}}
            if(region!=null) {weight+=.15;if(region.equals(p.getRegion().getRegionId())) {sum+=.15;reasons.add("Vùng chè: "+p.getRegion().getName());}}
            BigDecimal target=seed==null?null:seed.getPrice();
            if(q.minPrice()!=null&&q.maxPrice()!=null)target=q.minPrice().add(q.maxPrice()).divide(BigDecimal.valueOf(2));
            else if(q.maxPrice()!=null)target=q.maxPrice();else if(q.minPrice()!=null)target=q.minPrice();
            if(target!=null&&target.signum()>0) {weight+=.20;sum+=.20*p.getPrice().min(target).divide(p.getPrice().max(target),8,RoundingMode.HALF_UP).doubleValue();
                reasons.add(q.maxPrice()!=null||q.minPrice()!=null?"Trong ngân sách đã chọn":"So sánh mức giá với sản phẩm đang xem");}
            Integer[] actual={p.getStrengthLevel(),p.getAstringencyLevel(),p.getAromaLevel(),p.getAftertasteLevel()};
            Integer[] desired={q.strength(),q.astringency(),q.aroma(),q.aftertaste()};
            Integer[] previous=seed==null?new Integer[4]:new Integer[]{seed.getStrengthLevel(),seed.getAstringencyLevel(),seed.getAromaLevel(),seed.getAftertasteLevel()};
            String[] labels={"Độ đậm","Độ chát","Hương thơm","Hậu vị"};
            for(int i=0;i<4;i++) {
                Integer d=desired[i]!=null?desired[i]:previous[i];
                if(valid(d)) {
                    // Missing data has no fabricated value; it cannot count as a perfect match.
                    weight+=.075;
                    if(valid(actual[i])) {sum+=.075*(1-Math.abs(actual[i]-d)/4.0);reasons.add(labels[i]+": "+actual[i]+"/5");}
                    else reasons.add("Chưa có dữ liệu: "+labels[i].toLowerCase(Locale.ROOT));
                }
            }
            if(q.purpose()!=null) {
                // Purpose only compares supplied catalog text. Never invent gift packaging or daily-use benefits.
                String text=TeaText.normalize(p.getName()+" "+Objects.toString(p.getTasteNote(),"")+" "+Objects.toString(p.getDescription(),""));
                String term=q.purpose().equals("GIFT")?"qua":"hang ngay";
                weight+=.10;if((" "+text+" ").contains(" "+term+" ")){sum+=.10;reasons.add("Mô tả sản phẩm có thông tin phù hợp nhu cầu");}
            }
            if(seed!=null||message!=null) {
                Set<String> tokens=TeaText.tokens(message!=null?message:seed.getName()+" "+Objects.toString(seed.getTasteNote(),""));
                if(!tokens.isEmpty()) {Set<String> other=TeaText.tokens(p.getName()+" "+Objects.toString(p.getTasteNote(),"")+" "+p.getCategory().getName()+" "+p.getRegion().getName());int matched=0;for(String word:tokens)if(other.contains(word))matched++;
                    weight+=.10;sum+=.10*matched/tokens.size();if(matched>0)reasons.add("Có từ khóa hương vị tương đồng");}
            }
            double score=weight==0?0:sum/weight;
            if(reasons.isEmpty())reasons.add("Sản phẩm đang hiển thị và còn hàng; chưa đủ thông tin để so khớp khẩu vị");
            var summary=new ProductSummaryResponse(p.getProductId(),p.getName(),p.getSlug(),p.getPrice(),p.getWeightGrams(),p.getImageUrl(),p.getTasteNote(),p.getCategory().getCategoryId(),p.getCategory().getName(),p.getRegion().getRegionId(),p.getRegion().getName());
            items.add(new TeaRecommendationResponse.Item(summary,p.getStockQuantity(),BigDecimal.valueOf(score).setScale(4,RoundingMode.HALF_UP),List.copyOf(reasons)));
        }
        items.sort(Comparator.comparing(TeaRecommendationResponse.Item::score).reversed().thenComparing(i->i.product().productId(),Comparator.reverseOrder()));
        boolean fallback=items.isEmpty()||items.stream().allMatch(i->i.score().signum()==0);
        return new TeaRecommendationResponse("CONTENT_BASED_V1",fallback,items.stream().limit(limit).toList());
    }
    private static boolean valid(Integer n) {return n!=null&&n>=1&&n<=5;}
}
