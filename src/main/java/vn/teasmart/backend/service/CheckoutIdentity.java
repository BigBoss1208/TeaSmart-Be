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
    public static String buyNowHash(PlaceOrderRequest r, PaymentMethod method, Long productId, Long quantity) {
        return digest("BUY_NOW|" + productId + "|" + quantity + "|" + hash(r, method));
    }
    public static String hash(PlaceOrderRequest r, PaymentMethod method) {
        // Length-prefix fields so delimiter characters cannot cause collisions.
        StringBuilder data = new StringBuilder(method.name());
        for (String value : new String[] {r.recipientName(), r.recipientPhone(), r.shippingAddress(), r.note()}) {
            data.append('|').append(value == null ? -1 : value.length()).append(':');
            if (value != null) data.append(value);
        }
        return digest(data.toString());
    }
    public static String sessionHash(vn.teasmart.backend.dto.request.CreateCheckoutSessionRequest r) {
        StringBuilder data = new StringBuilder("SESSION|").append(r.sourceType()).append('|')
                .append(hash(r.shipping(), PaymentMethod.COD));
        if (r.independentPurchaseIntentId() != null) data.append("|INDEPENDENT_BUY_NOW|").append(r.independentPurchaseIntentId());
        if (r.buyNowProductId() != null || r.buyNowQuantity() != null) {
            data.append("|BUY_NOW|").append(r.buyNowProductId()).append('|').append(r.buyNowQuantity());
        }
        if (r.selectedItems() != null) {
            r.selectedItems().stream().sorted(java.util.Comparator.comparing(
                    vn.teasmart.backend.dto.request.SelectedCheckoutRequest.Item::cartItemId))
                    .forEach(item -> data.append("|SELECTED|").append(item.cartItemId()).append(':').append(item.quantity()));
        }
        if (r.buyAgainItems() != null) {
            r.buyAgainItems().stream().sorted(java.util.Comparator.comparing(
                    vn.teasmart.backend.dto.request.CreateCheckoutSessionRequest.BuyAgainItem::productId))
                    .forEach(item -> data.append("|BUY_AGAIN|").append(item.productId()).append(':').append(item.quantity()));
        }
        return digest(data.toString());
    }
    public static String selectedHash(vn.teasmart.backend.dto.request.SelectedCheckoutRequest r, PaymentMethod method) {
        if(r==null || r.shipping()==null || r.items()==null || r.items().isEmpty() || r.items().size()>100) throw PaymentException.invalid();
        java.util.TreeMap<Long,Long> values=new java.util.TreeMap<>();
        for(var item:r.items()) {
            if(item==null || item.cartItemId()==null || item.cartItemId()<1 || item.quantity()==null || item.quantity()<1 || values.putIfAbsent(item.cartItemId(),item.quantity())!=null) throw PaymentException.invalid();
        }
        StringBuilder data=new StringBuilder("SELECTED|").append(hash(r.shipping(),method));
        values.forEach((id,q)->data.append('|').append(id).append(':').append(q));
        return digest(data.toString());
    }
    private static String digest(String data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
