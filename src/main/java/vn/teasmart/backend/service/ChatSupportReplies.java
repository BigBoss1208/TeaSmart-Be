package vn.teasmart.backend.service;
/** Read-only help verified against customer order and payment workflows. */
final class ChatSupportReplies  {
    private ChatSupportReplies()  {
    }
    static String answer(String text) {
        if(text.matches("(?s).*(huy don|dat nham).*"))return "Bạn vào Tài khoản → Đơn hàng của tôi, mở chi tiết đơn và chọn Hủy nếu nút này khả dụng. Hiện chỉ đơn COD đang chờ xác nhận, chưa thanh toán mới được hủy trực tiếp. Đơn đang giao hoặc đã thanh toán không hỗ trợ hủy bằng chức năng này. Mình chỉ hướng dẫn, không tự hủy đơn.";
        if(text.matches("(?s).*(theo doi don|xem don|don hang.*(o dau|trang thai)|kiem tra.*don).*"))return "Bạn đăng nhập tài khoản đã đặt hàng, vào Tài khoản → Đơn hàng của tôi rồi mở chi tiết đơn để xem trạng thái. Mình chưa tra cứu đơn cá nhân trong cuộc trò chuyện này.";
        if(text.matches("(?s).*(thanh toan|vnpay|tra tien).*"))return "TeaSmart hỗ trợ COD và VNPay Sandbox trong giao diện hiện tại. Chọn phương thức tại Checkout; VNPay chuyển sang cổng thanh toán. Quay lại hoặc đóng cổng không chứng minh đơn đã thanh toán hay đã hủy. Nếu kết quả chưa xác minh, dùng chức năng kiểm tra trạng thái và tránh gửi thanh toán lặp. Xem đơn đã tạo trong Tài khoản → Đơn hàng của tôi.";
        if(text.matches("(?s).*(cach dat hang|lam.*mua che|muon dat hang|them.*gio|lam sao.*mua).*"))return "Bạn mở sản phẩm, chọn số lượng rồi Thêm vào giỏ hoặc Mua ngay. Tại Giỏ hàng, chọn sản phẩm cần mua; ở Checkout chọn địa chỉ nhận hàng, COD hoặc VNPay và xác nhận. Với VNPay, kiểm tra kết quả xác minh trước khi thử thanh toán lại.";
        if(text.matches("(?s).*(danh gia san pham|binh luan|lam sao.*danh gia).*"))return "Bạn đăng nhập tài khoản đã mua sản phẩm, vào mục Đánh giá của tôi trong Tài khoản. Hệ thống kiểm tra sản phẩm thuộc đơn đã giao; nội dung gửi lên chờ xét duyệt. Không phải mọi tài khoản đều có quyền đánh giá mọi sản phẩm.";
        return null;
    }
}
