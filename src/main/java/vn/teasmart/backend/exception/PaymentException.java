package vn.teasmart.backend.exception;

import org.springframework.http.HttpStatus;

public class PaymentException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public PaymentException(String code, String message, HttpStatus status) {
        super(message); this.code = code; this.status = status;
    }
    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public static PaymentException conflict(String code) {
        return new PaymentException(code, "Payment cannot be processed in its current state.", HttpStatus.CONFLICT);
    }
    public static PaymentException invalid() {
        return new PaymentException("INVALID_PAYMENT_REQUEST", "Invalid payment request.", HttpStatus.BAD_REQUEST);
    }
}
