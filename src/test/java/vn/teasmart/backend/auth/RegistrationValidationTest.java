package vn.teasmart.backend.auth;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.dto.request.RegisterRequest;
import vn.teasmart.backend.util.VietnamesePhone;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationValidationTest {
    @Test void canonicalFormsAndWhitespace() {
        assertEquals("0912345678", VietnamesePhone.normalize(" +84 912 345 678 "));
        assertEquals("0912345678", VietnamesePhone.normalize(" 0912\t345678\n"));
        assertEquals("0912345678", VietnamesePhone.normalize("\u00a00912345678\u00a0"));
        assertNull(VietnamesePhone.normalize(null));
        assertEquals("0912-345678", VietnamesePhone.normalize("0912-345678"));
    }

    @Test void passwordRegistrationRequiresValidPhone() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String phone : new String[]{"0912345678", "+84912345678", " +84 912 345 678 "}) {
                var request = new RegisterRequest("Fixture", " test@example.invalid ", "offline-password", phone);
                assertTrue(validator.validate(request).isEmpty());
                assertEquals("0912345678", request.phone());
            }
            for (String phone : new String[]{null, "", "   ", "0912-345678", "0123456789", "09123456789", "84912345678", "+840912345678"}) {
                assertTrue(validator.validate(new RegisterRequest("Fixture", "test@example.invalid", "offline-password", phone))
                        .stream().anyMatch(e -> e.getPropertyPath().toString().equals("phone")));
            }
        }
    }
}
