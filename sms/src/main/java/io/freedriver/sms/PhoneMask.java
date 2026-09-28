package io.freedriver.sms;

/** Log form of a phone number: at most its last four digits. */
public final class PhoneMask {

    private PhoneMask() {
    }

    public static String mask(String phone) {
        if (phone == null) {
            return "***";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() <= 4) {
            return "***";
        }
        return "***" + digits.substring(digits.length() - 4);
    }
}
