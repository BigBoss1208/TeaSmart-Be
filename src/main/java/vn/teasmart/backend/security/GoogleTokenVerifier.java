package vn.teasmart.backend.security;

/** Only returns claims after verification. Never accepts a client-supplied profile. */
public interface GoogleTokenVerifier {
    record Identity(String sub, String email, String name) { }
    Identity verify(String idToken, String expectedNonce);
}
