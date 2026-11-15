package vn.teasmart.backend.util;

import java.util.Locale;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;

/** Shared login identifier rules; registration rules remain unchanged. */
public final class LoginIdentifier {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private record EmailValue(@Email String value) { }
    private LoginIdentifier() { }

    public static boolean isEmail(String input) {
        return input != null && input.contains("@");
    }

    public static String normalize(String input) {
        if (input == null) return null;
        return isEmail(input) ? input.trim().toLowerCase(Locale.ROOT) : VietnamesePhone.normalize(input);
    }

    public static boolean isValid(String input) {
        String normalized = normalize(input);
        if (normalized == null || normalized.isBlank() || normalized.length() > 255) return false;
        return isEmail(normalized)
                ? VALIDATOR.validate(new EmailValue(normalized)).isEmpty()
                : normalized.matches("^0[35789][0-9]{8}$");
    }
}
