package vn.teasmart.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

public final class ReviewAiInputHasher {
    private ReviewAiInputHasher() { }

    public static String hash(String comment, Integer rating) {
        String normalized = comment == null ? null : Normalizer.normalize(comment, Normalizer.Form.NFC);
        String canonical = "review-ai-input-v1\n" + rating + "\n"
                + (normalized == null ? "NULL" : "TEXT:" + normalized.getBytes(StandardCharsets.UTF_8).length + ":" + normalized);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }
}
