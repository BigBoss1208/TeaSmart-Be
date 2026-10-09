package vn.teasmart.backend.exception;

import org.springframework.http.HttpStatus;

public class ReviewAiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ReviewAiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
