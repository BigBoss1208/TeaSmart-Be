package vn.teasmart.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import vn.teasmart.backend.dto.request.PlaceOrderRequest;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.exception.PaymentException;

public final class CheckoutIdentity {
    private CheckoutIdentity() { }
    public static String key(String value, boolean required) {
        if (value == null && !required) return null;
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,64}")) throw PaymentException.invalid();
        return value;
    }
    public static String hash(PlaceOrderRequest r, PaymentMethod method) {
        // Length-prefix fields so delimiter characters cannot cause collisions.
        StringBuilder data = new StringBuilder(method.name());
        for (String value : new String[] {r.recipientName(), r.recipientPhone(), r.shippingAddress(), r.note()}) {
            data.append('|').append(value == null ? -1 : value.length()).append(':');
            if (value != null) data.append(value);
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
