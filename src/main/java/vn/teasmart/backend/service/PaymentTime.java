package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class PaymentTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private PaymentTime() { }
    public static LocalDateTime now() { return LocalDateTime.now(ZONE).withNano(0); }
}
