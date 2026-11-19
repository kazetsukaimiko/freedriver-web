package io.freedriver.sms.validation;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one definition of a US phone number: the stored form, and the typed shapes that become it.
 * A longer string that contains one of these shapes is not a phone.
 */
public final class UsPhoneNumbers {

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

    /** Compiled from {@link UsPhoneNumber#REGEX}, so the stored form has one pattern string. */
    private static final Pattern CANONICAL = Pattern.compile(UsPhoneNumber.REGEX);

    private UsPhoneNumbers() {
    }

    /** True for {@code +1} and ten digits, the form {@link UsPhoneNumber} validates. */
    public static boolean isCanonical(String value) {
        return value != null && CANONICAL.matcher(value).matches();
    }

    /** The stored form for one of the eight typed shapes; empty for anything else. */
    public static Optional<String> canonical(String typed) {
        if (typed == null || !SHAPE.matcher(typed).matches()) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder(11);
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
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
