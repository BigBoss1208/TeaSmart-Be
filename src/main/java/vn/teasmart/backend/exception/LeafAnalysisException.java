package vn.teasmart.backend.exception;

import org.springframework.http.HttpStatus;

public final class LeafAnalysisException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public LeafAnalysisException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static LeafAnalysisException invalidResponse() {
        return new LeafAnalysisException(HttpStatus.BAD_GATEWAY, "LEAF_INVALID_UPSTREAM_RESPONSE",
                "Dịch vụ phân loại lá chè trả về phản hồi không hợp lệ.");
    }
}
