package io.freedriver.sms.api;

public record ErrorResponse(String error) {

    public static final ErrorResponse UNAUTHORIZED = new ErrorResponse("unauthorized");
    public static final ErrorResponse BAD_REQUEST = new ErrorResponse("bad-request");
    public static final ErrorResponse INVALID_CODE = new ErrorResponse("invalid-code");
    public static final ErrorResponse RATE_LIMITED = new ErrorResponse("rate-limited");
    public static final ErrorResponse UNAVAILABLE = new ErrorResponse("unavailable");
}
