package vn.teasmart.backend.payment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.LinkedMultiValueMap;
import vn.teasmart.backend.config.VnpaySettings;
import vn.teasmart.backend.dto.request.PlaceOrderRequest;
import vn.teasmart.backend.entity.Payment;
import vn.teasmart.backend.enums.PaymentMethod;
import vn.teasmart.backend.service.*;
import vn.teasmart.backend.exception.PaymentException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VnpayGatewayTest {
    private final String secret = UUID.randomUUID().toString();
    private VnpayGateway gateway() { return new VnpayGateway(new VnpaySettings(true, "TESTONLY", secret, "http://localhost:3000/payment/vnpay-return", 15)); }
    private Map<String, String> notification() {
        return new TreeMap<>(Map.of("vnp_TmnCode", "TESTONLY", "vnp_Amount", "10000000",
                "vnp_TxnRef", "TSVNP123", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00",
                "vnp_TransactionNo", "123", "vnp_PayDate", VnpayGateway.DATE.format(PaymentTime.now())));
    }
    private LinkedMultiValueMap<String, String> signed(Map<String, String> data) {
        var result = new LinkedMultiValueMap<String, String>(); data.forEach(result::add);
        result.add("vnp_SecureHash", gateway().sign(VnpayGateway.canonical(data))); return result;
    }
    @Test void hmacMatchesIndependentJcaAndUrlEncoding() throws Exception {
        String data = VnpayGateway.canonical(Map.of("z", "a+b &", "a", "Tiếng Việt"));
        var mac = javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8))), gateway().sign(data));
        assertTrue(data.startsWith("a=Ti%E1%BA%BFng+Vi%E1%BB%87t&z="));
    }
    @Test void signedSuccessRequiresBothCodes() {
        var data = notification(); assertTrue(gateway().verifyIpn(signed(data)).success());
        data.put("vnp_TransactionStatus", "01"); assertFalse(gateway().verifyIpn(signed(data)).success());
        data.put("vnp_TransactionStatus", "00"); data.put("vnp_ResponseCode", "07"); assertFalse(gateway().verifyIpn(signed(data)).success());
    }
    @Test void tamperingAndDuplicateParametersAreRejected() {
        var input = signed(notification()); input.set("vnp_Amount", "1");
        assertEquals("97", assertThrows(VnpayGateway.InvalidNotification.class, () -> gateway().verifyIpn(input)).code());
        var duplicate = signed(notification()); duplicate.add("vnp_TxnRef", "another");
        assertThrows(VnpayGateway.InvalidNotification.class, () -> gateway().verifyIpn(duplicate));
    }
    @Test void signedInvalidMerchantCurrencyAndDateAreRejected() {
        var data = notification(); data.put("vnp_TmnCode", "OTHERONE");
        var merchant = signed(data); assertThrows(VnpayGateway.InvalidNotification.class, () -> gateway().verifyIpn(merchant));
        data = notification(); data.put("vnp_CurrCode", "USD"); var currency = signed(data);
        assertThrows(VnpayGateway.InvalidNotification.class, () -> gateway().verifyIpn(currency));
        data = notification(); data.put("vnp_PayDate", "20260230120000"); var date = signed(data);
        assertThrows(VnpayGateway.InvalidNotification.class, () -> gateway().verifyIpn(date));
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.001", "10000000000.00"})
    void invalidAmountsRejected(String amount) { assertThrows(PaymentException.class, () -> VnpayGateway.amount(new BigDecimal(amount))); }
    @Test void urlUsesStoredReferenceAmountAndExpiry() {
        Payment p = new Payment(); p.setAmount(new BigDecimal("12345.67")); p.setMerchantReference("TSVNPabc123");
        p.setCreatedAt(LocalDateTime.of(2026, 10, 9, 12, 0)); p.setExpiresAt(p.getCreatedAt().plusMinutes(15));
        URI url = URI.create(gateway().paymentUrl(p, "127.0.0.1"));
        assertEquals("sandbox.vnpayment.vn", url.getHost());
        var values = new TreeMap<String, String>();
        for (String pair : url.getRawQuery().split("&")) { var parts = pair.split("=", 2); values.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8)); }
        String signature = values.remove("vnp_SecureHash");
        assertTrue(gateway().validSignature(VnpayGateway.canonical(values), signature));
        assertEquals("1234567", values.get("vnp_Amount")); assertEquals("20261009121500", values.get("vnp_ExpireDate"));
        assertEquals("TSVNPabc123", values.get("vnp_TxnRef"));
    }
    @Test void disabledGatewayFailsBeforeCheckout() {
        assertThrows(PaymentException.class, () -> new VnpaySettings(false, "", "", "", 15).requireConfigured());
    }
    @Test void requestHashIncludesMethodAndLengthPrefixedFields() {
        var a = new PlaceOrderRequest(" A ", "0912345678", "B|C", null);
        var b = new PlaceOrderRequest("A", "0912345678", "B|C", null);
        assertEquals(CheckoutIdentity.hash(a, PaymentMethod.COD), CheckoutIdentity.hash(b, PaymentMethod.COD));
        assertNotEquals(CheckoutIdentity.hash(a, PaymentMethod.COD), CheckoutIdentity.hash(a, PaymentMethod.ONLINE));
        assertThrows(PaymentException.class, () -> CheckoutIdentity.key(null, true));
        assertNull(CheckoutIdentity.key(null, false));
    }
    @Test void localIpv6LoopbackIsCanonicalizedForGatewayFieldLength() {
        Payment p = new Payment(); p.setAmount(new BigDecimal("100000")); p.setMerchantReference("TSVNPiptest");
        p.setCreatedAt(PaymentTime.now()); p.setExpiresAt(p.getCreatedAt().plusMinutes(15));
        for (String ip : List.of("::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
            var url = URI.create(gateway().paymentUrl(p, ip));
            var data = new TreeMap<String,String>();
            for (String pair : url.getRawQuery().split("&")) {
                var parts = pair.split("=", 2); data.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            assertEquals("127.0.0.1", data.get("vnp_IpAddr"));
            String signature = data.remove("vnp_SecureHash");
            assertTrue(gateway().validSignature(VnpayGateway.canonical(data), signature));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"999.999.999.999", "1.2.3", "beef", "1:2:3", "::ffff:999.1.1.1", "127.0.0.1, 8.8.8.8", "localhost"})
    void invalidIpNeverProducesGatewayUrl(String ip) {
        Payment p = new Payment(); p.setAmount(new BigDecimal("100000")); p.setMerchantReference("TSVNPiptest");
        p.setCreatedAt(PaymentTime.now()); p.setExpiresAt(p.getCreatedAt().plusMinutes(15));
        assertThrows(PaymentException.class, () -> gateway().paymentUrl(p, ip));
    }

    @ParameterizedTest @ValueSource(strings = {"110000.00", "120000.00", "240000.00", "234567.89"})
    void completePay21ContractRemainsCanonical(String amount) throws Exception {
        Payment p = new Payment(); p.setAmount(new BigDecimal(amount));
        p.setMerchantReference("TSVNP0123456789abcdef0123456789abcdef");
        p.setCreatedAt(LocalDateTime.of(2026, 10, 22, 18, 1, 44));
        p.setExpiresAt(p.getCreatedAt().plusMinutes(15));
        var settings = new VnpaySettings(true, "TESTONLY", secret,
                "https://merchant.invalid/payment/vnpay-return", 15);
        String raw = new VnpayGateway(settings).paymentUrl(p, "127.0.0.1");
        URI url = URI.create(raw);
        assertEquals("https", url.getScheme());
        assertEquals("/paymentv2/vpcpay.html", url.getPath());
        var values = new TreeMap<String, String>();
        var keys = new ArrayList<String>();
        for (String pair : url.getRawQuery().split("&")) {
            var parts = pair.split("=", 2); keys.add(parts[0]);
            assertNull(values.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
        }
        String signature = values.remove("vnp_SecureHash"); keys.remove("vnp_SecureHash");
        assertEquals(new ArrayList<>(values.keySet()), keys);
        assertEquals(Set.of("vnp_Version", "vnp_Command", "vnp_TmnCode", "vnp_Amount",
                "vnp_CurrCode", "vnp_TxnRef", "vnp_OrderInfo", "vnp_OrderType", "vnp_Locale",
                "vnp_ReturnUrl", "vnp_IpAddr", "vnp_CreateDate", "vnp_ExpireDate"), values.keySet());
        assertEquals("2.1.0", values.get("vnp_Version")); assertEquals("pay", values.get("vnp_Command"));
        assertEquals("vn", values.get("vnp_Locale")); assertEquals("VND", values.get("vnp_CurrCode"));
        assertEquals("other", values.get("vnp_OrderType"));
        assertTrue(values.get("vnp_OrderInfo").matches("[A-Za-z0-9 ]{1,255}"));
        assertTrue(values.get("vnp_TxnRef").matches("[A-Za-z0-9]{1,100}"));
        assertEquals(new BigDecimal(amount).movePointRight(2).toBigIntegerExact().toString(), values.get("vnp_Amount"));
        assertEquals("20261022180144", values.get("vnp_CreateDate"));
        assertEquals("20261022181644", values.get("vnp_ExpireDate"));
        assertEquals(settings.returnUrl(), values.get("vnp_ReturnUrl"));
        assertTrue(signature.matches("[0-9a-f]{128}"));
        String canonical = VnpayGateway.canonical(values);
        assertEquals(canonical + "&vnp_SecureHash=" + signature, url.getRawQuery());
        var mac = javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))), signature);
    }

}
