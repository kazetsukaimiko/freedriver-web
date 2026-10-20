package io.freedriver.keycloak.sms;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whole-entry US shapes for the phone-or-username router. Not
 * {@link PhoneSignInPolicy#normalizePhone}, which only strips separators.
 * A longer string that contains one of these shapes is not a phone.
 */
final class UsPhoneNumbers {

    /**
     * Ten-digit national number, or the same number with a leading US country
     * code written as {@code 1-} or {@code +1}. Separators are only the ones
     * in each listed shape.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "[0-9]{10}"
                    + "|[0-9]{3}-[0-9]{3}-[0-9]{4}"
                    + "|\\([0-9]{3}\\) [0-9]{3}-[0-9]{4}"
                    + "|[0-9]{3}\\.[0-9]{3}\\.[0-9]{4}"
                    + "|[0-9]{3} [0-9]{3} [0-9]{4}"
                    + "|1-[0-9]{3}-[0-9]{3}-[0-9]{4}"
                    + "|\\+1 [0-9]{3} [0-9]{3} [0-9]{4}"
                    + "|\\+1[0-9]{10}");

    private static final Pattern CANONICAL = Pattern.compile("\\+1[0-9]{10}");

    private UsPhoneNumbers() {}

    /** {@code +1} and the ten digits, or empty when {@code raw} is not one of the shapes. */
    static Optional<String> canonical(String raw) {
        if (raw == null || !SHAPE.matcher(raw).matches()) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder(11);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String national;
        if (digits.length() == 10) {
            national = digits.toString();
        } else if (digits.length() == 11 && digits.charAt(0) == '1') {
            national = digits.substring(1);
        } else {
            return Optional.empty();
        }
        String canonical = "+1" + national;
        return CANONICAL.matcher(canonical).matches() ? Optional.of(canonical) : Optional.empty();
    }
}
