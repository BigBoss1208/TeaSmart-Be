package vn.teasmart.backend.service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import vn.teasmart.backend.config.VnpaySettings;
import vn.teasmart.backend.entity.Payment;
import vn.teasmart.backend.exception.PaymentException;

@Component
public class VnpayGateway {
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> ALLOWED = Set.of("vnp_TmnCode", "vnp_Amount", "vnp_BankCode",
            "vnp_BankTranNo", "vnp_CardType", "vnp_PayDate", "vnp_OrderInfo", "vnp_TransactionNo",
            "vnp_ResponseCode", "vnp_TransactionStatus", "vnp_TxnRef", "vnp_SecureHash", "vnp_CurrCode");
    private final VnpaySettings settings;
    public VnpayGateway(VnpaySettings settings) { this.settings = settings; }

    public String paymentUrl(Payment payment, String ip) {
        settings.requireConfigured();
        if (ip == null || !ip.matches("[0-9a-fA-F:.]{2,45}")) throw PaymentException.invalid();
        Map<String, String> values = new TreeMap<>();
        values.put("vnp_Version", "2.1.0"); values.put("vnp_Command", "pay");
        values.put("vnp_TmnCode", settings.tmnCode()); values.put("vnp_CurrCode", "VND");
        values.put("vnp_Amount", amount(payment.getAmount()));
        values.put("vnp_TxnRef", payment.getMerchantReference());
        values.put("vnp_OrderInfo", "Thanh toan TeaSmart " + payment.getMerchantReference());
        values.put("vnp_OrderType", "other"); values.put("vnp_Locale", "vn");
        values.put("vnp_IpAddr", ip); values.put("vnp_ReturnUrl", settings.returnUrl());
        values.put("vnp_CreateDate", DATE.format(payment.getCreatedAt()));
        values.put("vnp_ExpireDate", DATE.format(payment.getExpiresAt()));
        String data = canonical(values);
        return VnpaySettings.PAYMENT_URL + "?" + data + "&vnp_SecureHash=" + sign(data);
    }

    public Notification verifyIpn(MultiValueMap<String, String> input) {
        settings.requireConfigured();
        Map<String, String> values = new TreeMap<>();
        input.forEach((key, entries) -> {
            if (!ALLOWED.contains(key) || entries.size() != 1 || entries.get(0) == null
                    || entries.get(0).length() > 512) throw new InvalidNotification("99");
            values.put(key, entries.get(0));
        });
        String signature = values.remove("vnp_SecureHash");
        if (!validSignature(canonical(values), signature)) throw new InvalidNotification("97");
        if (!settings.tmnCode().equals(values.get("vnp_TmnCode"))) throw new InvalidNotification("99");
        if (values.containsKey("vnp_CurrCode") && !"VND".equals(values.get("vnp_CurrCode"))) throw new InvalidNotification("04");
        return notification(values);
    }

    public Notification notification(Map<String, String> values) {
        String reference = required(values, "vnp_TxnRef", "[A-Za-z0-9]{1,100}");
        String rawAmount = required(values, "vnp_Amount", "[0-9]{1,12}");
        String response = required(values, "vnp_ResponseCode", "[0-9]{2}");
        String status = required(values, "vnp_TransactionStatus", "[0-9]{2}");
        String transaction = required(values, "vnp_TransactionNo", "[0-9]{1,15}");
        LocalDateTime paidAt = null;
        if ("00".equals(response) && "00".equals(status)) {
            if (transaction.chars().allMatch(c -> c == '0')) throw new InvalidNotification("99");
            try { paidAt = LocalDateTime.parse(required(values, "vnp_PayDate", "[0-9]{14}"), DATE); }
            catch (java.time.DateTimeException e) { throw new InvalidNotification("99"); }
            if (paidAt.isAfter(PaymentTime.now().plusMinutes(5))) throw new InvalidNotification("99");
        }
        return new Notification(reference, new BigDecimal(rawAmount).movePointLeft(2), response, status, transaction, paidAt);
    }

    private String required(Map<String, String> values, String key, String pattern) {
        String value = values.get(key);
        if (value == null || !value.matches(pattern)) throw new InvalidNotification("99");
        return value;
    }
    public static String amount(BigDecimal amount) {
        try {
            String result = amount.movePointRight(2).toBigIntegerExact().toString();
            if (amount.signum() <= 0 || !result.matches("[0-9]{1,12}")) throw PaymentException.invalid();
            return result;
        } catch (ArithmeticException e) { throw PaymentException.invalid(); }
    }
    public static String canonical(Map<String, String> values) {
        return new TreeMap<>(values).entrySet().stream().filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Payment signer unavailable."); }
    }
    public boolean validSignature(String data, String signature) {
        return signature != null && signature.matches("[a-fA-F0-9]{128}") && MessageDigest.isEqual(
                HexFormat.of().parseHex(sign(data)), HexFormat.of().parseHex(signature));
    }
    public record Notification(String reference, BigDecimal amount, String responseCode,
            String transactionStatus, String transactionNo, LocalDateTime paidAt) {
        public boolean success() { return "00".equals(responseCode) && "00".equals(transactionStatus); }
    }
    public static class InvalidNotification extends RuntimeException {
        private final String code;
        public InvalidNotification(String code) { super("Invalid gateway notification."); this.code = code; }
        public String code() { return code; }
    }
}
