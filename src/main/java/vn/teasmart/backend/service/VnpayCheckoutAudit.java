package vn.teasmart.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VnpayCheckoutAudit {
    public enum Source { RETURN, IPN, QUERYDR }

    private static final Logger LOG = LoggerFactory.getLogger(VnpayCheckoutAudit.class);
    private final JdbcTemplate jdbc;

    public VnpayCheckoutAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void callback(Source source, VnpayGateway.Notification notification, String verification, String outcome) {
        write(source, notification == null ? null : notification.reference(), verification, outcome,
                notification == null ? null : notification.responseCode(),
                notification == null ? null : notification.transactionStatus(), null, null, null);
    }

    public void query(String reference, VnpayQueryClient.QueryResult query, String outcome) {
        write(Source.QUERYDR, reference, query.outcome(), outcome, query.gatewayResponseCode(),
                query.gatewayTransactionStatus(), query.httpStatus(), query.requestSent(), query.elapsedMs());
    }

    private void write(Source source, String reference, String verification, String outcome, String response,
            String status, Integer httpStatus, Boolean requestSent, Long elapsedMs) {
        try {
            jdbc.update("""
                    INSERT INTO vnpay_checkout_audit
                        (source, merchant_reference, payment_attempt_id, checkout_session_id, verification_outcome,
                         processing_outcome, gateway_response_code, gateway_transaction_status, http_status,
                         request_sent, elapsed_ms)
                    SELECT ?, ?, a.payment_attempt_id, a.checkout_session_id, ?, ?, ?, ?, ?, ?, ?
                    FROM (SELECT 1) AS event
                    LEFT JOIN payment_attempts a ON a.merchant_reference = ?
                    """, source.name(), reference, verification, outcome, code(response), code(status), httpStatus,
                    requestSent, elapsedMs, reference);
        } catch (DataAccessException failure) {
            // Preserve the gateway acknowledgment. Log SQL classification, never exception text/SQL parameters.
            Throwable cause = failure;
            while (!(cause instanceof java.sql.SQLException) && cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            java.sql.SQLException sql = cause instanceof java.sql.SQLException e ? e : null;
            LOG.error("VNPay audit storage failure sqlState={} vendorCode={}",
                    sql == null ? "unavailable" : sql.getSQLState(), sql == null ? null : sql.getErrorCode());
            LOG.error("VNPay audit write failed source={} reference={} verification={} outcome={} response={} status={} httpStatus={} requestSent={} elapsedMs={}",
                    source, reference, verification, outcome, code(response), code(status), httpStatus, requestSent, elapsedMs);
        }
    }

    private static String code(String value) {
        return value != null && value.matches("[0-9]{2}") ? value : null;
    }
}
