package vn.teasmart.backend.service;

import java.util.List;
import java.util.regex.Pattern;

import static vn.teasmart.backend.service.ChatIntentClassifier.classify;

/** Rebuilds a standalone public knowledge question from recent customer turns.
 * Only vocabulary supported by the tea knowledge base can become a subject. */
final class TeaKnowledgeConversation {
    private TeaKnowledgeConversation() {}

    enum Action { KNOWLEDGE, CATALOG, CLARIFY }
    record Route(Action action, String question) {}
    private enum Focus {
        BREW("Cách pha %s như thế nào?"),
        SECOND_BREW("Khi pha %s, pha nước thứ hai thế nào?"),
        TEMPERATURE("Nhiệt độ nước và thời gian hãm khi pha %s là gì?"),
        STORAGE("Cách bảo quản %s sau khi mở bao bì như thế nào?"),
        PROCESS("Quy trình thu hái và chế biến %s như thế nào?"),
        ORIGIN("Vùng chè và nguồn gốc của %s có đặc điểm gì?"),
        COMPARE("Đặc điểm để phân biệt %s là gì?"),
        GENERAL("Tài liệu giới thiệu gì về %s?");

        final String format;
        Focus(String format) { this.format = format; }
        String question(String subject) { return format.formatted(subject == null ? "chè" : subject); }
    }

    private static final List<String> SUBJECTS = List.of("Tân Cương", "La Bằng", "Trại Cài", "Khe Cốc",
            "Đồng Phúc", "Thái Nguyên", "chè đinh", "chè nõn tôm", "chè móc câu", "trà xanh Nhật Bản");
    private static final Pattern REFERENCE = Pattern.compile("\\b(loai (?:che |tra )?(?:nay|do)|che (?:nay|do)|tra (?:nay|do)|no)\\b");
    private static final Pattern SECOND = Pattern.compile("\\b(?:pha (?:lai|(?:nuoc |lan )(?:thu )?(?:hai|2))|nuoc (?:thu )?(?:hai|2))\\b|^(?:the con )?lan (?:thu )?(?:hai|2)(?: thi sao)?$");
    private static final Pattern WHY = Pattern.compile("(?:(?:vi|tai) sao(?: lai)? (?:vay|the)|(?:vi|tai) sao|the con thi sao)(?: a)?");
    private static final Pattern BUY = Pattern.compile("\\b(?:co ban|muon mua|mua (?:loai|che|tra)|ban (?:loai|che|tra)|con hang)\\b");

    static Route resolve(String input, List<String> history) {
        String text = normalized(input);
        String named = subject(text);
        boolean reference = REFERENCE.matcher(text).find();
        Focus focus = explicitFocus(text);
        boolean why = WHY.matcher(text).matches();
        boolean buy = BUY.matcher(text).find();
        if (focus == null && !why && !(buy && reference)) return null;
        if (buy && !reference) return null;

        String previousSubject = null;
        Focus previousFocus = null;
        if (history != null) {
            for (String turn : history) {
                String previous = normalized(turn);
                String previousName = subject(previous);
                if (previousName != null) previousSubject = previousName;
                Focus described = explicitFocus(previous);
                if (described != null && previousName == null && !REFERENCE.matcher(previous).find()
                        && described != Focus.SECOND_BREW) previousSubject = null;
                if (BUY.matcher(previous).find() || previous.matches(".*\\b(ngan sach|duoi|gia bao nhieu|het hang)\\b.*")) {
                    if (previousName == null && !REFERENCE.matcher(previous).find()
                            && described == null && previousFocus != null) previousSubject = null;
                    previousFocus = null;
                } else if (described != null) {
                    previousFocus = described;
                } else if (previousName != null) {
                    previousFocus = classify(turn, false) == ChatIntentClassifier.Intent.TEA_KNOWLEDGE
                            ? Focus.GENERAL : null;
                } else if (!WHY.matcher(previous).matches()
                        && (ChatSupportReplies.answer(previous) != null
                            || classify(turn,false) == ChatIntentClassifier.Intent.OUT_OF_SCOPE)) {
                    previousSubject = null;
                    previousFocus = null;
                }
            }
        }
        String topic = named == null ? previousSubject : named;
        if (buy) return topic == null ? clarify()
                : new Route(Action.CATALOG, topic);
        if (why) return previousFocus == null ? clarify()
                : new Route(Action.KNOWLEDGE, "Vì sao? " + previousFocus.question(topic));
        if (focus == Focus.SECOND_BREW && named == null && previousFocus != Focus.BREW
                && previousFocus != Focus.SECOND_BREW && previousFocus != Focus.TEMPERATURE) return clarify();
        if (reference && topic == null) return clarify();
        return new Route(Action.KNOWLEDGE,
                focus.question(reference || focus == Focus.SECOND_BREW ? topic : named));
    }

    static String standalone(String input) {
        String text = normalized(input);
        Focus focus = explicitFocus(text);
        if (focus == null) {
            focus = switch (classify(input, false)) {
                case TEA_ORIGIN -> Focus.ORIGIN;
                case BREWING_GUIDE -> Focus.BREW;
                default -> text.contains("so sanh") || text.contains("khac nhau") ? Focus.COMPARE : Focus.GENERAL;
            };
        }
        List<String> subjects = subjects(text);
        return focus.question(subjects.isEmpty() ? null
                : String.join(" và ", subjects.stream().map(TeaKnowledgeConversation::catalogSubject).toList()));
    }

    private static Route clarify() { return new Route(Action.CLARIFY, null); }

    private static Focus explicitFocus(String text) {
        if (text.matches(".*\\b(bao quan|cat giu)\\b.*")) return Focus.STORAGE;
        if (SECOND.matcher(text).find() && text.matches(".*\\b(pha|nuoc|lan)\\b.*")) return Focus.SECOND_BREW;
        if (text.matches(".*\\b(nhiet do|thoi gian ham)\\b.*")) return Focus.TEMPERATURE;
        if (text.matches(".*\\b(cach pha|pha che|pha tra|pha nhu|ham che|ham tra)\\b.*")) return Focus.BREW;
        if (text.matches(".*\\b(che bien|thu hai che|thu hai tra)\\b.*")) return Focus.PROCESS;
        return null;
    }

    private static String subject(String text) {
        List<String> names = subjects(text);
        return names.isEmpty() ? null : catalogSubject(names.get(0));
    }

    private static List<String> subjects(String text) {
        String padded = " " + text + " ";
        return SUBJECTS.stream().filter(name -> {
            String key = normalized(name).replaceFirst("^(che|tra) ", "");
            return padded.contains(" " + key + " ") || name.equals("trà xanh Nhật Bản") && padded.contains(" tra xanh nhat ");
        }).limit(2).toList();
    }

    private static String catalogSubject(String name) {
        return name.startsWith("chè ") || name.startsWith("trà ") ? name : "chè " + name;
    }

    private static String normalized(String text) {
        return TeaText.normalize(text).replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").trim();
    }
}
