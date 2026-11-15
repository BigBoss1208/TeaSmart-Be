package vn.teasmart.backend.notification;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.entity.Order;
import vn.teasmart.backend.entity.OrderItem;
import vn.teasmart.backend.entity.Payment;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.enums.PaymentStatus;

@Component
public class OrderNotifications {
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    public OrderNotifications(ApplicationEventPublisher events,
            @Value("${teasmart.mail.enabled:false}") boolean enabled) {
        this.events = events; this.enabled = enabled;
    }
    public void welcome(User user) {
        if (!enabled) return;
        publish(user.getEmail(), "Chào mừng đến với TeaSmart",
                "<p>Xin chào " + escape(user.getFullName()) + ",</p><p>Tài khoản TeaSmart của bạn đã được tạo thành công. Cảm ơn bạn đã đồng hành cùng trà Thái Nguyên.</p>");
    }
    public void loggedIn(User user, boolean google) {
        if (!enabled) return;
        String time = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy"));
        publish(user.getEmail(), "TeaSmart — Đăng nhập thành công",
                "<p>Xin chào " + escape(user.getFullName()) + ",</p>"
                + "<p>Tài khoản TeaSmart của bạn vừa đăng nhập thành công.</p>"
                + "<p>Thời gian: <strong>" + time + "</strong> (giờ Việt Nam, UTC+07:00).</p>"
                + "<p>Phương thức: " + (google ? "Google" : "Email hoặc số điện thoại và mật khẩu") + ".</p>"
                + "<p>Nếu đây là bạn, bạn có thể bỏ qua thông báo này. Nếu bạn không thực hiện đăng nhập, "
                + "vui lòng bảo vệ tài khoản và liên hệ cửa hàng TeaSmart để được hỗ trợ.</p>");
    }
    public void placed(Order order, List<OrderItem> items, Payment payment) {
        if (!enabled) return;
        StringBuilder content = new StringBuilder(summary(order, payment));
        content.append("<table style=\"width:100%;border-collapse:collapse\"><tr><th align=\"left\">Sản phẩm</th><th>Số lượng</th><th>Thành tiền</th></tr>");
        for (OrderItem item : items) content.append("<tr><td>").append(escape(item.getProductName()))
                .append("</td><td align=\"center\">").append(item.getQuantity())
                .append("</td><td align=\"right\">").append(money(item.getSubtotal())).append("</td></tr>");
        content.append("</table>");
        publish(order.getUser().getEmail(), "TeaSmart — Xác nhận đặt hàng", content.toString());
    }
    public void payment(Order order, Payment payment) {
        if (!enabled || payment.getPaymentMethod() != PaymentMethod.ONLINE || !"VNPAY".equals(payment.getGateway())) return;
        if (payment.getPaymentStatus() == PaymentStatus.PAID) {
            String extra = payment.isReconciliationRequired()
                    ? "<p>Khoản thanh toán đã được ghi nhận nhưng đơn hàng cần đối soát. Cửa hàng sẽ kiểm tra; thông báo này không xác nhận đơn đã được tiếp tục giao.</p>" : "";
            publish(order.getUser().getEmail(), "TeaSmart — Đã ghi nhận thanh toán", summary(order, payment) + extra);
        } else if (payment.getPaymentStatus() == PaymentStatus.FAILED && !payment.isReconciliationRequired()) {
            publish(order.getUser().getEmail(), "TeaSmart — Thanh toán không thành công", summary(order, payment)
                    + "<p>Kết quả thất bại/hủy đã được xác minh. Đơn hàng đã được hủy.</p>");
        }
    }
    public void statusChanged(Order order, Payment payment) {
        if (!enabled) return;
        publish(order.getUser().getEmail(), "TeaSmart — Cập nhật đơn hàng", summary(order, payment));
    }
    private String summary(Order order, Payment payment) {
        return "<p>Mã đơn: <strong>" + escape(order.getOrderCode()) + "</strong></p>"
                + "<p>Tổng tiền: " + money(order.getTotalAmount()) + "</p><p>Phương thức: "
                + (payment.getPaymentMethod() == PaymentMethod.COD ? "COD — Thanh toán khi nhận hàng" : "VNPay Sandbox")
                + "</p><p>Thanh toán: " + switch (payment.getPaymentStatus()) {
                    case PAID -> "Đã thanh toán";
                    case FAILED -> "Không thành công đã xác minh";
                    case PENDING -> "Đang chờ thanh toán/xác minh";
                } + "</p><p>Trạng thái đơn: " + switch (order.getOrderStatus()) {
                    case "PENDING" -> "Chờ xác nhận"; case "CONFIRMED" -> "Đã xác nhận";
                    case "SHIPPING" -> "Đang giao hàng"; case "DELIVERED" -> "Đã giao hàng";
                    case "CANCELLED" -> "Đã hủy"; default -> "Cần kiểm tra";
                } + "</p>";
    }
    private void publish(String to, String subject, String body) {
        events.publishEvent(new EmailNotification(to, subject,
                "<!doctype html><html lang=\"vi\"><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<body style=\"margin:0;background:#f6f3eb;color:#203c30;font-family:Arial,sans-serif\">"
                + "<main style=\"max-width:600px;margin:auto;padding:24px;background:#fff\"><h1 style=\"color:#154734\">TeaSmart</h1>"
                + body + "<hr><p>Hương trà Thái Nguyên · Thông báo tự động từ TeaSmart.</p></main></body></html>"));
    }
    static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String money(BigDecimal value) {
        var format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"));
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(0);
        return format.format(value);
    }
}
