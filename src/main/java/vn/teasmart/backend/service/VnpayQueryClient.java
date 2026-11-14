package vn.teasmart.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.time.Instant;
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
        return query(p.reference(), p.createdAt());
    }

    public VnpayGateway.Notification queryAttempt(CheckoutSessionService.AttemptSnapshot p) {
        return query(p.reference(), p.createdAt());
    }

    /** Diagnostic QueryDR call. Never includes the request, signature, or merchant reference. */
    public QueryResult queryAttemptDiagnostic(CheckoutSessionService.AttemptSnapshot p) {
        long started = System.nanoTime();
        boolean sent = false;
        Integer httpStatus = null;
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                return new QueryResult("TRANSACTION_GUARD", false, null, 0, null, null);
            settings.requireConfigured();
            Map<String, String> body = new LinkedHashMap<>();
            body.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
            body.put("vnp_Version", "2.1.0"); body.put("vnp_Command", "querydr");
            body.put("vnp_TmnCode", settings.tmnCode()); body.put("vnp_TxnRef", p.reference());
            body.put("vnp_TransactionDate", VnpayGateway.DATE.format(p.createdAt()));
            body.put("vnp_CreateDate", VnpayGateway.DATE.format(PaymentTime.now()));
            body.put("vnp_IpAddr", "127.0.0.1"); body.put("vnp_OrderInfo", "Doi soat TeaSmart " + p.reference());
            body.put("vnp_SecureHash", gateway.sign(String.join("|", body.values())));
            var request = HttpRequest.newBuilder(URI.create(VnpaySettings.QUERY_URL)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            sent = true;
            var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response;
            try { response = pending.get(12, java.util.concurrent.TimeUnit.SECONDS); }
            finally { pending.cancel(true); }
            httpStatus = response.statusCode();
            if (httpStatus != 200) return queryResult("HTTP_ERROR", sent, httpStatus, started, null);
            if (response.body().length > 65536) return queryResult("INVALID_RESPONSE", sent, httpStatus, started, null);
            QueryParseResult parsed = parseResponseDiagnostic(response.body(), p.reference());
            return new QueryResult(parsed.outcome(), sent, httpStatus,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    parsed.responseCode(), parsed.transactionStatus(), parsed.notification());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return queryResult("TIMEOUT_OR_INTERRUPTED", sent, httpStatus, started, null);
        } catch (java.util.concurrent.TimeoutException e) {
            return queryResult("TIMEOUT", sent, httpStatus, started, null);
        } catch (vn.teasmart.backend.exception.PaymentException e) {
            return queryResult("GATEWAY_NOT_CONFIGURED", sent, httpStatus, started, null);
        } catch (IllegalStateException e) {
            return queryResult("NOT_CONFIGURED_OR_GUARD_REJECTED", sent, httpStatus, started, null);
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.ExecutionException && e.getCause() instanceof java.net.http.HttpTimeoutException)
                return queryResult("TIMEOUT", sent, httpStatus, started, null);
            return queryResult("NETWORK_OR_CLIENT_ERROR", sent, httpStatus, started, null);
        }
    }

    private QueryResult queryResult(String outcome, boolean sent, Integer status, long started,
            VnpayGateway.Notification notification) {
        return new QueryResult(outcome, sent, status,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                notification == null ? null : notification.responseCode(),
                notification == null ? null : notification.transactionStatus(), notification);
    }

    public record QueryResult(String outcome, boolean requestSent, Integer httpStatus, long elapsedMs,
            String gatewayResponseCode, String gatewayTransactionStatus, VnpayGateway.Notification notification) {
        public QueryResult(String outcome, boolean requestSent, Integer httpStatus, long elapsedMs,
                String responseCode, String transactionStatus) {
            this(outcome, requestSent, httpStatus, elapsedMs, responseCode, transactionStatus, null);
        }
    }

    private VnpayGateway.Notification query(String reference, java.time.LocalDateTime createdAt) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Gateway HTTP calls must execute outside database transactions.");
        settings.requireConfigured();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("vnp_RequestId", UUID.randomUUID().toString().replace("-", ""));
        body.put("vnp_Version", "2.1.0"); body.put("vnp_Command", "querydr");
        body.put("vnp_TmnCode", settings.tmnCode()); body.put("vnp_TxnRef", reference);
        body.put("vnp_TransactionDate", VnpayGateway.DATE.format(createdAt));
        body.put("vnp_CreateDate", VnpayGateway.DATE.format(PaymentTime.now()));
        body.put("vnp_IpAddr", "127.0.0.1"); body.put("vnp_OrderInfo", "Doi soat TeaSmart " + reference);
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
            return parseResponse(response.body(), reference);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (Exception e) { return null; } // No raw request/response, signature, or secret logging.
    }
    public VnpayGateway.Notification parseResponse(byte[] body, PaymentService.Snapshot p) {
        return parseResponse(body, p.reference());
    }

    public VnpayGateway.Notification parseResponse(byte[] body, CheckoutSessionService.AttemptSnapshot p) {
        return parseResponse(body, p.reference());
    }

    private VnpayGateway.Notification parseResponse(byte[] body, String expectedReference) {
        return parseResponseDiagnostic(body, expectedReference).notification();
    }

    private QueryParseResult parseResponseDiagnostic(byte[] body, String expectedReference) {
        try {
            if (body.length > 65536) return new QueryParseResult("INVALID_RESPONSE", null, null, null);
            var node = json.readTree(body);
            if (!node.isObject()) return new QueryParseResult("INVALID_RESPONSE", null, null, null);
            Map<String, String> values = new HashMap<>();
            for (String field : RESPONSE_FIELDS) {
                var value = node.get(field);
                if (value != null && !value.isString()) return new QueryParseResult("INVALID_RESPONSE", null, null, null);
                values.put(field, value == null ? "" : value.stringValue());
            }
            var signature = node.get("vnp_SecureHash");
            if (signature == null || signature.isNull() || (signature.isString() && signature.stringValue().isBlank()))
                return new QueryParseResult("SIGNATURE_MISSING", null, null, null);
            if (!signature.isString() || !signature.stringValue().matches("[0-9a-fA-F]{128}"))
                return new QueryParseResult("SIGNATURE_MALFORMED", null, null, null);
            if (!gateway.validSignature(
                    RESPONSE_FIELDS.stream().map(values::get).collect(java.util.stream.Collectors.joining("|")), signature.stringValue()))
                return new QueryParseResult("SIGNATURE_REJECTED", null, null, null);
            if (!settings.tmnCode().equals(values.get("vnp_TmnCode")))
                return new QueryParseResult("SIGNED_MERCHANT_MISMATCH", null, null, null);
            if (!expectedReference.equals(values.get("vnp_TxnRef")))
                return new QueryParseResult("SIGNED_REFERENCE_MISMATCH", null, null, null);
            if (!"querydr".equals(values.get("vnp_Command")) || !"01".equals(values.get("vnp_TransactionType")))
                return new QueryParseResult("SIGNED_PROTOCOL_MISMATCH", null, null, null);
            if (!"00".equals(values.get("vnp_ResponseCode")))
                return new QueryParseResult("VERIFIED_QUERY_REJECTED", null, values.get("vnp_ResponseCode"), values.get("vnp_TransactionStatus"));
            // Query response code 00 means QueryDR succeeded. Preserve verified transaction status;
            // PaymentService still decides which statuses are final for this merchant.
            if (!values.get("vnp_TransactionStatus").matches("[0-9]{2}"))
                return new QueryParseResult("VERIFIED_QUERY_MALFORMED_TRANSACTION_STATUS", null, "00", null);
            if (values.get("vnp_TransactionNo").isBlank()) values.put("vnp_TransactionNo", "0");
            try {
                return new QueryParseResult("VERIFIED_RESPONSE", gateway.notification(values), "00", values.get("vnp_TransactionStatus"));
            } catch (RuntimeException invalidNotification) {
                return new QueryParseResult("VERIFIED_QUERY_INVALID_TRANSACTION", null, "00", values.get("vnp_TransactionStatus"));
            }
        } catch (RuntimeException e) { return new QueryParseResult("INVALID_RESPONSE", null, null, null); }
    }

    private record QueryParseResult(String outcome, VnpayGateway.Notification notification,
            String responseCode, String transactionStatus) {}

}
