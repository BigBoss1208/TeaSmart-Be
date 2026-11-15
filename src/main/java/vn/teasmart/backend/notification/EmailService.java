package vn.teasmart.backend.notification;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    public EmailService(JavaMailSender sender,
            @Value("${teasmart.mail.enabled:false}") boolean enabled,
            @Value("${teasmart.mail.from:}") String from) {
        this.sender = sender; this.enabled = enabled; this.from = from;
        if (enabled && (from.isBlank() || from.contains("\r") || from.contains("\n")))
            throw new IllegalArgumentException("Enabled email requires a valid MAIL_FROM.");
    }
    // No fallback execution: publishing outside a transaction must never send mail.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(EmailNotification notification) {
        if (!enabled) return;
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(from, "TeaSmart"); helper.setTo(notification.recipient());
            helper.setSubject(notification.subject()); helper.setText(notification.html(), true);
            sender.send(message);
        } catch (Exception failure) {
            // SMTP errors can contain recipient addresses/credentials; never log the exception.
            log.warn("EMAIL_DELIVERY_FAILED");
        }
    }
}
