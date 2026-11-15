package vn.teasmart.backend.util;

/** Canonical form shared by registration and future phone authentication. */
public final class VietnamesePhone {
    private VietnamesePhone() { }

    public static String normalize(String input) {
        if (input == null) return null;
        String phone = input.replaceAll("(?U)\\s+", "");
        return phone.startsWith("+84") ? "0" + phone.substring(3) : phone;
    }
}
