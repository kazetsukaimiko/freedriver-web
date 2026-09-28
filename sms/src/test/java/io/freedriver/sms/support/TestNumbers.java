package io.freedriver.sms.support;

import java.util.concurrent.atomic.AtomicInteger;

/** Placeholder 555 numbers; each call hands out a fresh one. */
public final class TestNumbers {

    private static final AtomicInteger NEXT = new AtomicInteger(100);

    private TestNumbers() {
    }

    public static String fresh() {
        return "+1555555%04d".formatted(NEXT.getAndIncrement());
    }

    public static final String SID_ACCOUNT = "AC" + "0".repeat(32);
    public static final String SID_API_KEY = "SK" + "1".repeat(32);
    public static final String API_KEY_SECRET = "test-api-key-secret-not-live";
    public static final String SID_SERVICE = "VA" + "2".repeat(32);
    public static final String WORDING = "By tapping Send code, you agree to receive a one-time sign-in code by text from Freedriver. "
            + "Message and data rates may apply. Reply STOP to opt out.";
}
