package vn.teasmart.backend.exception;

public class InvalidProductNameException extends RuntimeException {
    public InvalidProductNameException() {
        super("Product name must produce a non-empty slug of at most 250 characters.");
    }
}
