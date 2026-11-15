package vn.teasmart.backend.exception;

public class GoogleAuthException extends RuntimeException {
    private final int status;
    private final String code;
    private GoogleAuthException(int status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public static GoogleAuthException failed() {
        return new GoogleAuthException(401,"GOOGLE_AUTH_FAILED","Google authentication failed.");
    }
    public static GoogleAuthException unavailable() {
        return new GoogleAuthException(503,"GOOGLE_AUTH_UNAVAILABLE","Google authentication is temporarily unavailable.");
    }
    public static GoogleAuthException conflict() {
        return new GoogleAuthException(409,"GOOGLE_LINK_REQUIRED","Sign in using your existing TeaSmart credentials.");
    }
}
