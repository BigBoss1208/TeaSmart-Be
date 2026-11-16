package vn.teasmart.backend.service;

/** Short follow-ups inherit preferences; an explicit new topic always owns routing. */
final class ChatConversation {
    private ChatConversation() {}

    static boolean isFollowUp(String text) {
        return text.matches("(?s).*(tang bo|tang me|nguoi than|thay co|doi tac|it chat|hau ngot|dam vi|vi dam|che dam|re hon|re nhat|thu hai|xem san pham|duoi|toi da|ngan sach|doi y).*")
                || text.matches("[0-9].*") || text.matches("(tang|bieu) .*");
    }

    static boolean startsNewNeed(String text) {
        return text.matches("(?s)^(toi |minh )?(muon mua|muon tim|tim che|co che tan cuong).*");
    }
}
