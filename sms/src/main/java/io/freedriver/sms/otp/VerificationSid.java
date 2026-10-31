package io.freedriver.sms.otp;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/** A Twilio Verify verification sid: {@code VE} and 32 hex characters. */
public final class VerificationSid {

    private static final Pattern SID = Pattern.compile("VE[0-9a-fA-F]{32}");

    private VerificationSid() {
    }

    public static boolean matches(String value) {
        return value != null && SID.matcher(value).matches();
    }

    /** {@code VE***} plus the last four hex characters. Does not return a full sid. */
    public static String maskForLog(String sid) {
        if (!matches(sid)) {
            return "-";
        }
        return "VE***" + sid.substring(sid.length() - 4);
    }

    public static String random(SecureRandom random) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder("VE");
        for (byte b : bytes) {
            hex.append("%02x".formatted(b & 0xff));
        }
        return hex.toString();
    }
}
