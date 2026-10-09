package vn.teasmart.backend.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.exception.PaymentException;

@Component
public class VnpaySettings {
    public static final String PAYMENT_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    public static final String QUERY_URL = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";
    private final boolean enabled;
    private final String tmnCode;
    private final String secret;
    private final String returnUrl;
    private final int expiryMinutes;
    public VnpaySettings(@Value("${teasmart.vnpay.enabled:false}") boolean enabled,
            @Value("${teasmart.vnpay.tmn-code:}") String tmnCode,
            @Value("${teasmart.vnpay.hash-secret:}") String secret,
            @Value("${teasmart.vnpay.return-url:}") String returnUrl,
            @Value("${teasmart.vnpay.expiry-minutes:15}") int expiryMinutes) {
        this.enabled = enabled; this.tmnCode = tmnCode; this.secret = secret;
        this.returnUrl = returnUrl; this.expiryMinutes = expiryMinutes;
    }
    public void requireConfigured() {
        boolean valid = enabled && tmnCode.matches("[A-Za-z0-9]{8}") && !secret.isBlank()
                && expiryMinutes >= 5 && expiryMinutes <= 30;
        try {
            URI uri = URI.create(returnUrl);
            valid &= returnUrl.length() <= 255 && uri.getHost() != null && uri.getFragment() == null
                    && uri.getUserInfo() == null && ("https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()))));
        } catch (IllegalArgumentException e) { valid = false; }
        if (!valid) throw new PaymentException("VNPAY_UNAVAILABLE", "VNPay Sandbox is not configured.", HttpStatus.SERVICE_UNAVAILABLE);
    }
    public boolean isEnabled() { return enabled; }
    public String tmnCode() { return tmnCode; }
    public String secret() { return secret; }
    public String returnUrl() { return returnUrl; }
    public int expiryMinutes() { return expiryMinutes; }
}
