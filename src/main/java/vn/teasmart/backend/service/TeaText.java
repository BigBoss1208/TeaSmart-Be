package vn.teasmart.backend.service;

import java.text.Normalizer;
import java.util.*;

final class TeaText {
    private TeaText() {}
    static String normalize(String text) {
        return Normalizer.normalize(text==null?"":text.toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
                .replaceAll("\\p{M}+","").replace('đ','d').replaceAll("[^a-z0-9., -]"," ")
                .replaceAll(" +"," ").trim();
    }
    static Set<String> tokens(String text) {
        var words=new LinkedHashSet<>(Arrays.asList(normalize(text).split("[^a-z0-9]+")));
        words.removeAll(Set.of("","toi","minh","ban","muon","tim","che","tra","loai","san","pham","va","cho","voi","cua","mot"));
        return words;
    }
}
