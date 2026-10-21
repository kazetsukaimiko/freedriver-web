package io.freedriver.app.phones;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whole-entry US shapes from the phone-or-username router (#155). A longer string that
 * contains one of these shapes is not a phone.
 */
public final class UsPhones {

    private static final Pattern SHAPE = Pattern.compile(
            "[0-9]{10}"
                    + "|[0-9]{3}-[0-9]{3}-[0-9]{4}"
                    + "|\\([0-9]{3}\\) [0-9]{3}-[0-9]{4}"
                    + "|[0-9]{3}\\.[0-9]{3}\\.[0-9]{4}"
                    + "|[0-9]{3} [0-9]{3} [0-9]{4}"
                    + "|1-[0-9]{3}-[0-9]{3}-[0-9]{4}"
                    + "|\\+1 [0-9]{3} [0-9]{3} [0-9]{4}"
                    + "|\\+1[0-9]{10}");

    private UsPhones() {
    }

    /** {@code +1} and the ten digits, or empty when {@code raw} is not one of the shapes. */
    public static Optional<String> canonical(String raw) {
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
        return Optional.of("+1" + national);
    }

    /** {@code (123) 456-7890} for a canonical number. */
    public static String display(String canonical) {
        String national = canonical.substring(2);
        return "(" + national.substring(0, 3) + ") " + national.substring(3, 6) + "-" + national.substring(6);
    }
}
