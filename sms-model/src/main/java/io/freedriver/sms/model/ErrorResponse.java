package io.freedriver.sms.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A rejected call. Fields with no value are left out of the JSON. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String type) {

    public ErrorResponse(String error) {
        this(error, null, null);
    }

    public static final ErrorResponse UNAUTHORIZED = new ErrorResponse("unauthorized");
    public static final ErrorResponse BAD_REQUEST = new ErrorResponse("bad-request");
    public static final ErrorResponse INVALID_CODE = new ErrorResponse("invalid-code");
    public static final ErrorResponse RATE_LIMITED = new ErrorResponse("rate-limited");
    public static final ErrorResponse UNAVAILABLE = new ErrorResponse("unavailable");

    /** A verify rejection that names which of the three verify lines the page shows. */
    public static ErrorResponse rejected(String reason) {
        return new ErrorResponse("invalid-code", reason, SendType.OTP_NAME);
    }

    /** The check after the fifth attempt on the open verification. */
    public static ErrorResponse tooManyAttempts() {
        return new ErrorResponse("rate-limited", VerifyFailure.TOO_MANY_ATTEMPTS, SendType.OTP_NAME);
    }
}
