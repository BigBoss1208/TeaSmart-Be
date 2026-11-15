package vn.teasmart.backend.exception;

public class DuplicatePhoneException extends RuntimeException {
    public DuplicatePhoneException() {
        super("Số điện thoại đã được đăng ký.");
    }
}
