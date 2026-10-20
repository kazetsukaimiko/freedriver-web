package io.freedriver.sms.otp;

/**
 * Sends a sign-in code to a phone and checks a code the person typed. Implementations never
 * choose, store or log the code they send.
 */
public interface SmsSender {

    SendOutcome sendCode(String phone);

    CheckOutcome checkCode(String phone, String code);
}
