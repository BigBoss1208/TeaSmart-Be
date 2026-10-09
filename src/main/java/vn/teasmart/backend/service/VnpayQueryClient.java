package vn.teasmart.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.config.VnpaySettings;

@Component
public class VnpayQueryClient {
    private static final List<String> RESPONSE_FIELDS = List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode",
            "vnp_Message", "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate",
            "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo",
            "vnp_PromotionCode", "vnp_PromotionAmount");
    private final VnpaySettings settings;
    private final VnpayGateway gateway;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public VnpayQueryClient(VnpaySettings settings, VnpayGateway gateway) { this.settings = settings; this.gateway = gateway; }

    public VnpayGateway.Notification query(PaymentService.Snapshot p) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Gateway HTTP calls must execute outside database transactions.");
        settings.requireConfigured();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
        body.put("vnp_Version", "2.1.0"); body.put("vnp_Command", "querydr");
        body.put("vnp_TmnCode", settings.tmnCode()); body.put("vnp_TxnRef", p.reference());
        body.put("vnp_TransactionDate", VnpayGateway.DATE.format(p.createdAt()));
        body.put("vnp_CreateDate", VnpayGateway.DATE.format(PaymentTime.now()));
        body.put("vnp_IpAddr", "127.0.0.1"); body.put("vnp_OrderInfo", "Doi soat TeaSmart " + p.reference());
        body.put("vnp_SecureHash", gateway.sign(String.join("|", body.values())));
        try {
            var request = HttpRequest.newBuilder(URI.create(VnpaySettings.QUERY_URL)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response;
            try { response = pending.get(12, java.util.concurrent.TimeUnit.SECONDS); }
            finally { pending.cancel(true); }
            if (response.statusCode() != 200 || response.body().length > 65536) return null;
            return parseResponse(response.body(), p);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { return null; } // No raw request/response, signature, or secret logging.
    }
    public VnpayGateway.Notification parseResponse(byte[] body, PaymentService.Snapshot p) {
        try {
            if (body.length > 65536) return null;
            var node = json.readTree(body);
            if (!node.isObject()) return null;
            Map<String, String> values = new HashMap<>();
            for (String field : RESPONSE_FIELDS) {
                var value = node.get(field);
                if (value != null && !value.isString()) return null;
                values.put(field, value == null ? "" : value.stringValue());
            }
            var signature = node.get("vnp_SecureHash");
            if (signature == null || !signature.isString() || !gateway.validSignature(
                    RESPONSE_FIELDS.stream().map(values::get).collect(java.util.stream.Collectors.joining("|")), signature.stringValue())) return null;
            if (!settings.tmnCode().equals(values.get("vnp_TmnCode")) || !p.reference().equals(values.get("vnp_TxnRef"))
                    || !"querydr".equals(values.get("vnp_Command")) || !"01".equals(values.get("vnp_TransactionType"))
                    || !"00".equals(values.get("vnp_ResponseCode"))) return null;
            // Query response code 00 only means the query worked. Do not use it as payment success/finality.
            if (!"00".equals(values.get("vnp_TransactionStatus"))) return null;
            return gateway.notification(values);
        } catch (RuntimeException e) { return null; }
    }

}
