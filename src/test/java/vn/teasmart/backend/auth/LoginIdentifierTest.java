package vn.teasmart.backend.auth;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.dto.request.LoginRequest;
import vn.teasmart.backend.util.LoginIdentifier;
import static org.junit.jupiter.api.Assertions.*;

class LoginIdentifierTest {
    @Test void emailAndPhoneValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String input : new String[]{"test@example.invalid", " TEST@example.invalid ",
                    "0912345678", "+84912345678", " +84 912 345 678 ", "\u00a00912345678\u00a0"}) {
                assertTrue(factory.getValidator().validate(new LoginRequest(input, "fixture-password")).isEmpty(), input);
            }
            for (String input : new String[]{null, "", " ", "not-email", "a@@example.com", "0912-345678",
                    "0123456789", "84912345678", "+840912345678", "0912345678x", "x".repeat(256)}) {
                assertFalse(factory.getValidator().validate(new LoginRequest(input, "fixture-password")).isEmpty());
            }
        }
    }
    @Test void normalizationDoesNotAlterPasswordOrInvalidPunctuation() {
        assertEquals("test@example.invalid", LoginIdentifier.normalize(" TEST@example.invalid "));
        assertEquals("0912345678", LoginIdentifier.normalize(" +84 912 345 678 "));
        assertEquals("0912-345678", LoginIdentifier.normalize("0912-345678"));
        assertEquals(" password ", new LoginRequest("0912345678", " password ").password());
    }
}
