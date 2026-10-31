package io.freedriver.keycloak.sms;

import io.freedriver.sms.api.SmsApi;
import io.freedriver.sms.client.SmsCallContext;
import io.freedriver.sms.client.SmsClient;
import io.freedriver.sms.client.SmsClientConfig;
import io.freedriver.sms.client.SmsClientException;
import io.freedriver.sms.model.AgreementShown;
import io.freedriver.sms.model.OtpSendRequest;
import io.freedriver.sms.model.SendType;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.model.SmsTarget;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyFailure;
import io.freedriver.sms.model.VerifyRequest;

import java.util.function.Function;

/**
 * Maps {@link SmsClient} results onto the phone sign-in pages.
 * This is not an HTTP client. {@link SmsClient} owns the call.
 * The provider does not run a {@code Validator}. The sms service validates the body.
 */
final class SmsOtpCalls {

    enum Outcome {
        SENT,
        VERIFIED,
        INVALID_CODE,
        EXPIRED,
        TOO_MANY_ATTEMPTS,
        RATE_LIMITED,
        /** Verify answered 503 {@code unavailable}. */
        CHECK_UNAVAILABLE,
        UNAVAILABLE,
        /** {@code send} caught a {@link RuntimeException} that was not {@link SmsClientException}. */
        SWALLOWED
    }

    /** Per-number window the sms service sends as {@code Retry-After: 900}. */
    static final int DEFAULT_RETRY_AFTER_SECONDS = 900;
    static final int MAX_RETRY_AFTER_SECONDS = 3600;

    record Result(Outcome outcome, String phone, Integer retryAfterSeconds) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, null);
        }
    }

    private final Function<String, SmsApi> clients;

    SmsOtpCalls(Function<String, SmsApi> clients) {
        this.clients = clients;
    }

    static SmsOtpCalls production() {
        return new SmsOtpCalls(SmsOtpCalls::client);
    }

    /** Fixed base URL from this checkout's sms-client. The secret stays in {@link SmsClientConfig}. */
    static SmsApi client(String secret) {
        return SmsClient.create(SmsClientConfig.atFixedBase(secret));
    }

    Result send(String secret, String phone, String correlationId) {
        try {
            SmsCallContext.setCorrelationId(correlationId);
            SentResponse sent = clients.apply(secret).send(
                    new OtpSendRequest(phone, new AgreementShown(SignInAgreement.WORDING)));
            return sent(sent);
        } catch (SmsClientException ex) {
            return fromException(ex, false);
        } catch (RuntimeException ex) {
            return Result.of(Outcome.SWALLOWED);
        } finally {
            SmsCallContext.clear();
        }
    }

    Result verify(String secret, String phone, String code) {
        try {
            VerifiedResponse verified = clients.apply(secret).verify(new VerifyRequest(phone, code));
            return verified(verified);
        } catch (SmsClientException ex) {
            return fromException(ex, true);
        } catch (RuntimeException ex) {
            return Result.of(Outcome.UNAVAILABLE);
        }
    }

    /**
     * Same page outcomes as the removed hand-written client.
     * 401 and 403 are unavailable to the page, which already treats them as not sent.
     */
    static Result fromException(SmsClientException ex, boolean verify) {
        int status = ex.status();
        if (status == 429) {
            if (verify && VerifyFailure.TOO_MANY_ATTEMPTS.equals(ex.reason())) {
                return Result.of(Outcome.TOO_MANY_ATTEMPTS);
            }
            return new Result(Outcome.RATE_LIMITED, null, retrySeconds(ex.retryAfterSeconds()));
        }
        if (verify && status == 400 && "invalid-code".equals(ex.error())) {
            if (VerifyFailure.EXPIRED.equals(ex.reason())) {
                return Result.of(Outcome.EXPIRED);
            }
            return Result.of(Outcome.INVALID_CODE);
        }
        if (verify && status == 503 && "unavailable".equals(ex.error())) {
            return Result.of(Outcome.CHECK_UNAVAILABLE);
        }
        return Result.of(Outcome.UNAVAILABLE);
    }

    /**
     * Whole seconds from 1 to 3600. Anything else, including a missing header, is 900.
     * sms-client already drops non-numeric Retry-After values.
     */
    static int retrySeconds(Long retryAfterSeconds) {
        if (retryAfterSeconds == null) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        long value = retryAfterSeconds;
        if (value < 1 || value > MAX_RETRY_AFTER_SECONDS) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        return (int) value;
    }

    private static Result sent(SentResponse sent) {
        SmsTarget target = sent == null ? null : sent.sent();
        if (target == null || target.type() != SendType.OTP || target.phone() == null || target.phone().isEmpty()) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        return new Result(Outcome.SENT, target.phone(), null);
    }

    private static Result verified(VerifiedResponse verified) {
        SmsTarget target = verified == null ? null : verified.verified();
        if (target == null || target.type() != SendType.OTP || target.phone() == null || target.phone().isEmpty()) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        return new Result(Outcome.VERIFIED, target.phone(), null);
    }
}
