package vn.teasmart.backend.service;
/** Deterministic routing only; this classifier is not an LLM. Original input is preserved. */
public final class ChatIntentClassifier  {
    private ChatIntentClassifier()  {
    }
    public enum Intent  {
        PRODUCT_SEARCH, PRODUCT_DETAIL, PRODUCT_PRICE, PRODUCT_STOCK,
        PRODUCT_COMPARISON, BEST_SELLER, PRODUCT_RECOMMENDATION, TEA_KNOWLEDGE,
        TEA_ORIGIN, BREWING_GUIDE, PRODUCT_CONTEXT, OUT_OF_SCOPE, GREETING, UNKNOWN
    }
    private static boolean has(String text,String pattern)  {
        return text.matches("(?s).*(?:"+pattern+").*");
    }
    static boolean referencesCurrentProduct(String text) {
        return has(text, "\\b(san pham|che|tra|loai|cai) (nay|(?:(?:ma )?toi |ban )?(?:dang )?(?:mo|xem))\\b"
                + "|\\b(loai|cai) do\\b");
    }

    static boolean isCurrentProductFollowUp(String text) {
        return text.replaceAll("[^a-z0-9 ]", "").trim()
                .matches("(gia(?: la)? bao nhieu|bao nhieu tien|con hang(?: khong| ko)?|het hang(?: chua| khong)?|ton kho(?: bao nhieu)?)( a)?");
    }

    public static Intent classify(String input,boolean productContext)  {
        String t=TeaText.normalize(input).replaceAll("[^a-z0-9 ]"," ").replaceAll(" +"," ").trim();
        if(t.isBlank())return Intent.UNKNOWN;
        if(t.matches("(xin chao|chao)( ban| b)?|hello|hi|cam on|thank you"))return Intent.GREETING;
        boolean tea=has(t,"\\b(che|tra|tan cuong|la bang|trai cai|thai nguyen)\\b");
        if(has(t,"viet.*code|doan code|lap trinh|giai.*bai toan|bong da|gia vang|may gio|tin tuc") || !tea&&has(t,"thoi tiet|troi.*mua"))return Intent.OUT_OF_SCOPE;
        if(has(t,"best ?seller|betsetler|ban chay|hot|mua nhieu|nhieu nguoi mua|yeu thich nhat|top 5"))return Intent.BEST_SELLER;
        if(has(t,"co.*(che|tra)|muon mua|tim che|^che tan cuong$")&&!has(t,"dac biet|cach pha|bao quan"))return Intent.PRODUCT_SEARCH;
        if(has(t,"so sanh|khac.*nhau|khac.*the nao"))return productContext||has(t,"san pham")?Intent.PRODUCT_COMPARISON:Intent.TEA_KNOWLEDGE;
        if(has(t,"pha che|pha tra|nen pha|nhiet do|ham che|ham tra"))return Intent.BREWING_GUIDE;
        if(referencesCurrentProduct(t)) {
            if(has(t,"con hang|het hang|ton kho"))return Intent.PRODUCT_STOCK;
            if(has(t,"gia|bao nhieu tien")&&!has(t,"danh gia"))return Intent.PRODUCT_PRICE;
            return Intent.PRODUCT_DETAIL;
        }
        if(productContext && has(t,"nguon goc|lam qua|qua bieu|qua tang|cong dung|vi nhu|khoi luong|phu hop voi ai"))return Intent.PRODUCT_DETAIL;
        if(has(t,"danh gia cao|nhieu sao|noi bat|moi nhat"))return Intent.PRODUCT_SEARCH;
        if(has(t,"doi che|vung che|o dau|nguon goc|dia ly|khi hau"))return tea?Intent.TEA_ORIGIN:Intent.OUT_OF_SCOPE;
        if(has(t,"gia|bao nhieu tien")&&!has(t,"danh gia|duoi|ngan sach|toi da"))return Intent.PRODUCT_PRICE;
        if(has(t,"con hang|het hang|ton kho"))return Intent.PRODUCT_STOCK;
        if(has(t,"san pham nay|che nay|loai nay|dang xem|\\bno\\b|bao nhieu gram|bao nhieu gam|khoi luong|co dang|cong dung|vi nhu|dac biet"))return productContext||!tea||has(t,"nay|dang xem")?Intent.PRODUCT_DETAIL:Intent.TEA_KNOWLEDGE;
        if(has(t,"lam qua|qua bieu|qua tang|hang ngay|tuong tu|it chat|hau ngot|ngon nhat|loai.*ngon|phu hop"))return Intent.PRODUCT_RECOMMENDATION;
        if(has(t,"san pham|nen mua|muon mua|duoi|ngan sach|dam vi|gia toi da|tim che"))return Intent.PRODUCT_SEARCH;
        if(tea||has(t,"bao quan"))return Intent.TEA_KNOWLEDGE;
        if(productContext&&has(t,"cai nay|loai do"))return Intent.PRODUCT_CONTEXT;
        return Intent.OUT_OF_SCOPE;
    }
}
