package vn.teasmart.backend.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.enums.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailNotificationTest {
    private final List<EmailNotification> events = new ArrayList<>();
    private final OrderNotifications notifications = new OrderNotifications(event -> events.add((EmailNotification) event), true);
    private User user() { var u = new User(); u.setEmail("fixture@example.invalid"); u.setFullName("<script>& Chè"); return u; }
    private vn.teasmart.backend.entity.Order order() {
        var o = new vn.teasmart.backend.entity.Order(); o.setUser(user()); o.setOrderCode("TS-FIXTURE");
        o.setOrderStatus("PENDING"); o.setTotalAmount(new BigDecimal("250000.25")); return o;
    }
    private Payment payment(PaymentStatus status) {
        var p = new Payment(); p.setPaymentMethod(PaymentMethod.ONLINE); p.setGateway("VNPAY"); p.setPaymentStatus(status); return p;
    }
    @Test void welcomeEscapesUntrustedHtml() {
        notifications.welcome(user()); assertEquals(1, events.size());
        assertTrue(events.get(0).html().contains("&lt;script&gt;&amp; Chè"));
        assertFalse(events.get(0).html().contains("<script>"));
    }
    @Test void loginUsesAccountRecipientEscapesNameAndContainsNoCredentials() {
        var first = user();
        notifications.loggedIn(first, false);
        var second = user(); second.setEmail("second@example.invalid");
        notifications.loggedIn(second, true);
        assertEquals(first.getEmail(), events.get(0).recipient());
        assertEquals(second.getEmail(), events.get(1).recipient());
        assertEquals("TeaSmart — Đăng nhập thành công", events.get(0).subject());
        assertTrue(events.get(0).html().contains("&lt;script&gt;&amp; Chè"));
        assertTrue(events.get(0).html().contains("UTC+07:00"));
        assertTrue(events.get(0).html().contains("Email hoặc số điện thoại và mật khẩu"));
        assertTrue(events.get(1).html().contains("Phương thức: Google"));
        assertFalse(events.get(0).html().contains("<script>"));
    }
    @Test void placedSnapshotsItemsAndStatesPendingExplicitly() {
        var item = new OrderItem(); item.setProductName("<img src=x>"); item.setQuantity(2L); item.setSubtotal(new BigDecimal("250000.25"));
        notifications.placed(order(), List.of(item), payment(PaymentStatus.PENDING));
        var html = events.get(0).html(); assertTrue(html.contains("TS-FIXTURE"));
        assertTrue(html.contains("&lt;img src=x&gt;")); assertTrue(html.contains("Đang chờ thanh toán/xác minh"));
        assertTrue(html.contains("250.000,25"));
        assertTrue(html.contains("VNPay Sandbox")); assertTrue(html.contains(">2<"));
    }
    @Test void verifiedSuccessAndLateSuccessAreDistinguished() {
        var p = payment(PaymentStatus.PAID); notifications.payment(order(), p);
        assertTrue(events.get(0).subject().contains("Đã ghi nhận thanh toán"));
        p.setReconciliationRequired(true); notifications.payment(order(), p);
        assertTrue(events.get(1).html().contains("cần đối soát"));
    }
    @Test void unresolvedAndCodNeverSendPaymentSuccessOrFailure() {
        notifications.payment(order(), payment(PaymentStatus.PENDING));
        var cod = payment(PaymentStatus.PAID); cod.setPaymentMethod(PaymentMethod.COD); notifications.payment(order(), cod);
        var failed = payment(PaymentStatus.FAILED); failed.setReconciliationRequired(true); notifications.payment(order(), failed);
        assertTrue(events.isEmpty());
    }
    @Test void verifiedFailureAndAdminTransitionHaveVietnameseContent() {
        var o = order(); o.setOrderStatus("CANCELLED"); notifications.payment(o, payment(PaymentStatus.FAILED));
        assertTrue(events.get(0).html().contains("thất bại/hủy đã được xác minh"));
        o.setOrderStatus("SHIPPING"); notifications.statusChanged(o, payment(PaymentStatus.PAID));
        assertTrue(events.get(1).html().contains("Đang giao hàng"));
    }
    @Test void disabledNotificationsPublishNothing() {
        var disabled = new OrderNotifications(event -> fail("Disabled email published an event"), false);
        disabled.loggedIn(null, false); disabled.welcome(null); disabled.placed(null, null, null); disabled.statusChanged(null, null); disabled.payment(null, null);
    }
    @Test void mimeIsUtf8HtmlAndSmtpFailureDoesNotEscape() throws Exception {
        var sender = mock(JavaMailSender.class); var message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        var service = new EmailService(sender, true, "sender@example.invalid");
        notifications.welcome(user()); service.afterCommit(events.get(0));
        assertEquals("Chào mừng đến với TeaSmart", message.getSubject());
        assertTrue(message.getDataHandler().getContentType().toLowerCase().contains("text/html"));
        doThrow(new MailSendException("Controlled failure")).when(sender).send(message);
        assertDoesNotThrow(() -> service.afterCommit(events.get(0)));
    }
    @Test void disabledSenderDoesNotTouchSmtp() {
        var sender = mock(JavaMailSender.class);
        new EmailService(sender, false, "").afterCommit(new EmailNotification("fixture@example.invalid", "Test", "Test"));
        verifyNoInteractions(sender);
    }
    @Test void onlyCommittedTransactionsSendAndRollbackOrNoTransactionDoNot() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            var sender = context.getBean(JavaMailSender.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var event = new EmailNotification("fixture@example.invalid", "Test", "<p>Test</p>");
            context.publishEvent(event); verify(sender, never()).send(any(MimeMessage.class));
            tx.executeWithoutResult(status -> { context.publishEvent(event); status.setRollbackOnly(); });
            verify(sender, never()).send(any(MimeMessage.class));
            tx.executeWithoutResult(status -> { context.publishEvent(event); verify(sender, never()).send(any(MimeMessage.class)); });
            verify(sender, times(1)).send(any(MimeMessage.class));
            doThrow(new MailSendException("Controlled failure")).when(sender).send(any(MimeMessage.class));
            assertDoesNotThrow(() -> tx.executeWithoutResult(status -> context.publishEvent(event)));
            verify(sender, times(2)).send(any(MimeMessage.class));
        }
    }
    @Configuration
    static class TestConfig {
        @Bean JavaMailSender sender() {
            var sender = mock(JavaMailSender.class);
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties()))); return sender;
        }
        @Bean EmailService emailService(JavaMailSender sender) { return new EmailService(sender, true, "sender@example.invalid"); }
        @Bean PlatformTransactionManager transactionManager() { return new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        }; }
        @Bean static org.springframework.transaction.event.TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new org.springframework.transaction.event.TransactionalEventListenerFactory();
        }
    }
}
