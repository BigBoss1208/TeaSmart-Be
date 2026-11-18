package vn.teasmart.backend.service;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static vn.teasmart.backend.service.ChatIntentClassifier.classify;

/** Builds public RAG questions from recognized tea topics and recent user turns. */
final class TeaKnowledgeConversation {
    private TeaKnowledgeConversation() {}

    enum Action { KNOWLEDGE, CATALOG, CLARIFY }
    record Route(Action action, String question) {}

    private enum Focus {
        BREW("Cách pha %s như thế nào?"),
        SECOND_BREW("Khi pha %s, pha nước thứ hai thế nào?"),
        TEMPERATURE("Nhiệt độ nước và thời gian hãm khi pha %s là gì?"),
        STORAGE("Cách bảo quản %s sau khi mở bao bì như thế nào?"),
        STORAGE_REASON("Vì sao cần bảo quản %s đúng cách sau khi mở bao bì?"),
        SEALED_STORAGE_REASON("Vì sao nên bảo quản %s trong bao bì kín?"),
        PROCESS("Quy trình thu hái và chế biến %s như thế nào?"),
        ORIGIN("Vùng chè và nguồn gốc của %s có đặc điểm gì?"),
        HIGHLIGHTS("Điểm đặc biệt của %s là gì?"),
        INTRODUCTION("Vùng chè, nét đặc trưng và văn hóa của %s có gì đáng chú ý?"),
        TASTE("%s có hương vị như thế nào? Ngon hay không có tùy khẩu vị không?"),
        COMPARE("%s khác nhau thế nào?"),
        GENERAL("Tài liệu giới thiệu gì về %s?");

        final String format;
        Focus(String format) { this.format = format; }
        String question(List<String> subjects) {
            return format.formatted(subjects.isEmpty() ? "chè" : String.join(" và ", subjects));
        }

        boolean brewing() { return this == BREW || this == SECOND_BREW || this == TEMPERATURE; }
    }

    private static final List<String> REGIONS = List.of("Tân Cương", "La Bằng", "Trại Cài",
            "Khe Cốc", "Đồng Phúc", "Thái Nguyên");
    private static final List<String> SUBJECTS = java.util.stream.Stream.concat(REGIONS.stream(),
            java.util.stream.Stream.of("chè đinh", "chè nõn tôm", "chè móc câu", "trà xanh Nhật Bản"))
            .toList();
    private static final Pattern REFERENCE = Pattern.compile("\\b(loai (?:che |tra )?(?:nay|do)|che (?:nay|do)|tra (?:nay|do)|no|nhu vay|nhu the)\\b(?! nao)");
    private static final Pattern PAIR_REFERENCE = Pattern.compile("\\b(hai|2|ca hai) (?:loai|vung)(?: che| tra)?(?: nay| do)?\\b");
    private static final Pattern SECOND = Pattern.compile("\\b(?:pha (?:lai|(?:nuoc |lan )(?:thu )?(?:hai|2))|nuoc (?:thu )?(?:hai|2))\\b|^(?:the con )?lan (?:thu )?(?:hai|2)(?: thi sao)?$");
    private static final Pattern WHY = Pattern.compile("(?:(?:vi|tai) sao(?: lai)? (?:vay|the)|(?:vi|tai) sao|the con thi sao)(?: a)?");
    private static final Pattern BUY = Pattern.compile("\\b(?:co ban (?:che|tra|loai|san pham|khong)|(?:teasmart|cua hang|ben ban) (?:co )?ban|muon mua|mua (?:loai|che|tra)|ban (?:loai|che|tra)|con hang)\\b");
    private static final Pattern COMMERCE = Pattern.compile("\\b(ngan sach|gia (?:ban|bao nhieu|tot|re|dat|tien)|bao nhieu tien|het hang|ton kho|ban chay|mua o dau|goi y san pham)\\b|\\b(?:duoi|toi da|tu) \\d");
    private static final Pattern COMPARE = Pattern.compile("\\b(so sanh|khac nhau|khac gi|khac the nao)\\b");

    static Route resolve(String input, List<String> history, boolean productContext) {
        String text = normalized(input);
        List<String> named = subjects(text);
        boolean pair = PAIR_REFERENCE.matcher(text).find();
        boolean reference = REFERENCE.matcher(text).find() || pair;
        Focus focus = explicitFocus(text, named, productContext);
        boolean why = WHY.matcher(text).matches();
        boolean buy = BUY.matcher(text).find();
        if (focus == null && !why && !(buy && reference)) return null;
        if (buy && (!reference || productContext && text.contains("san pham"))) return null;

        List<String> prior = List.of();
        Focus previousFocus = null;
        if (history != null) {
            for (String turn : history) {
                String previous = normalized(turn);
                List<String> previousNames = subjects(previous);
                boolean previousReference = REFERENCE.matcher(previous).find() || PAIR_REFERENCE.matcher(previous).find();
                Focus described = explicitFocus(previous, previousNames, false);
                if (!previousNames.isEmpty()) prior = previousNames;
                if (BUY.matcher(previous).find() || COMMERCE.matcher(previous).find()) {
                    if (previousNames.isEmpty() && !previousReference && previousFocus != null) prior = List.of();
                    previousFocus = null;
                } else if (described != null) {
                    if (previousNames.isEmpty() && !previousReference && described != Focus.SECOND_BREW) prior = List.of();
                    previousFocus = described;
                } else if (!previousNames.isEmpty()) {
                    previousFocus = null;
                } else if (!WHY.matcher(previous).matches()
                        && (ChatSupportReplies.answer(previous) != null
                            || classify(turn, false) == ChatIntentClassifier.Intent.OUT_OF_SCOPE)) {
                    prior = List.of();
                    previousFocus = null;
                }
            }
        }

        List<String> topics = named.isEmpty() ? prior : named;
        if (buy) return topics.size() == 1 ? new Route(Action.CATALOG, topics.get(0)) : clarify();
        if (why) {
            if (previousFocus == null) return clarify();
            if (previousFocus == Focus.STORAGE || previousFocus == Focus.STORAGE_REASON)
                return new Route(Action.KNOWLEDGE, Focus.STORAGE_REASON.question(topics));
            return new Route(Action.KNOWLEDGE, "Vì sao? " + previousFocus.question(topics));
        }
        if (focus == Focus.SECOND_BREW && named.isEmpty() && (previousFocus == null || !previousFocus.brewing()))
            return clarify();
        if ((focus == Focus.COMPARE && topics.size() != 2) || (reference && topics.isEmpty())) return clarify();
        if ((focus == Focus.HIGHLIGHTS || focus == Focus.TASTE || focus == Focus.INTRODUCTION)
                && !reference && named.isEmpty()) return clarify();
        return new Route(Action.KNOWLEDGE, focus.question(reference || focus == Focus.SECOND_BREW ? topics : named));
    }

    static String standalone(String input) {
        String text = normalized(input);
        List<String> names = subjects(text);
        Focus focus = explicitFocus(text, names, false);
        if (focus == null) {
            focus = switch (classify(input, false)) {
                case TEA_ORIGIN -> Focus.ORIGIN;
                case BREWING_GUIDE -> Focus.BREW;
                default -> names.size() == 2 && COMPARE.matcher(text).find() ? Focus.COMPARE : Focus.GENERAL;
            };
        }
        return focus.question(names);
    }

    private static Route clarify() { return new Route(Action.CLARIFY, null); }

    private static Focus explicitFocus(String text, List<String> names, boolean productContext) {
        if (BUY.matcher(text).find() || COMMERCE.matcher(text).find()) return null;
        if (text.matches(".*\\b(bao quan|cat giu)\\b.*")) {
            if (!text.matches(".*\\b(vi|tai) sao\\b.*")) return Focus.STORAGE;
            return text.matches(".*\\b(kin|day nap|dong nap)\\b.*") ? Focus.SEALED_STORAGE_REASON : Focus.STORAGE_REASON;
        }
        if (SECOND.matcher(text).find()) return Focus.SECOND_BREW;
        if (text.matches(".*\\b(nhiet do|thoi gian ham)\\b.*")) return Focus.TEMPERATURE;
        if (text.matches(".*\\b(cach pha|pha che|pha tra|pha nhu|ham che|ham tra)\\b.*")) return Focus.BREW;
        if (text.matches(".*\\b(che bien|thu hai che|thu hai tra)\\b.*")) return Focus.PROCESS;
        if (text.matches(".*\\b(san pham|dang xem|dang mo)\\b.*")) return null;
        if (COMPARE.matcher(text).find()
                && (PAIR_REFERENCE.matcher(text).find()
                    || names.size() == 2 && names.stream().anyMatch(name -> name.startsWith("chè ") && REGIONS.contains(name.substring("chè ".length())))))
            return Focus.COMPARE;
        if (productContext && ChatIntentClassifier.referencesCurrentProduct(text)) return null;
        if (names.isEmpty() && !REFERENCE.matcher(text).find() && !text.matches(".*\\b(che|tra)\\b.*")) return null;
        if (text.matches(".*\\b(tim hieu ve|muon tim hieu|net dac trung|van hoa che)\\b.*")) return Focus.INTRODUCTION;
        if (text.matches(".*\\b(co gi dac biet|diem dac biet|diem noi bat|dac trung la gi)\\b.*")) return Focus.HIGHLIGHTS;
        if (text.matches(".*\\b(co ngon khong|co ngon ko|huong vi the nao|huong vi nhu the nao|vi the nao|vi nhu the nao)\\b.*")) return Focus.TASTE;
        return null;
    }

    private static List<String> subjects(String text) {
        String padded = " " + text + " ";
        return SUBJECTS.stream()
                .filter(name -> subjectIndex(padded, name) >= 0)
                .sorted(Comparator.comparingInt(name -> subjectIndex(padded, name)))
                .limit(2)
                .map(name -> name.startsWith("chè ") || name.startsWith("trà ") ? name : "chè " + name)
                .toList();
    }

    private static int subjectIndex(String padded, String name) {
        int match = padded.indexOf(" " + normalized(name).replaceFirst("^(che|tra) ", "") + " ");
        return match < 0 && name.equals("trà xanh Nhật Bản") ? padded.indexOf(" tra xanh nhat ") : match;
    }

    private static String normalized(String text) {
        return TeaText.normalize(text).replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").trim();
    }
}
