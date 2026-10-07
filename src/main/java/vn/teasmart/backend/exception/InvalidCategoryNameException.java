package vn.teasmart.backend.exception;

public class InvalidCategoryNameException extends RuntimeException {
    public InvalidCategoryNameException() {
        super("Category name must produce a non-empty slug.");
    }
}
