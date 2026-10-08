package vn.teasmart.backend.exception;

public class CartConflictException extends RuntimeException {
    private final String code;

    public CartConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
