package io.freedriver.sms.api;

public record SentResponse(String status) {

    public static final SentResponse SENT = new SentResponse("sent");
}
