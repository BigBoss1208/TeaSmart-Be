package vn.teasmart.backend.exception;

public class ProductConflictException extends RuntimeException {
    public ProductConflictException() {
        super("Product slug already exists. Choose another name.");
    }
}
