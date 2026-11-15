package io.freedriver.sms.security;

import io.freedriver.sms.model.VerifyFailure;
import io.freedriver.sms.model.VerifyRequest;
import io.freedriver.sms.otp.CodeRejectedException;
import io.freedriver.sms.otp.TooManyAttemptsException;
import io.freedriver.sms.otp.VerificationLedger;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * The wrong-code limit on a method marked {@code @PhoneRateLimited(CODE_CHECK)}, whose argument is
 * the validated {@link VerifyRequest}. The slot is taken before the check runs, so parallel guesses
 * stop at the limit. The method's outcome decides the slot:
 * <ul>
 * <li>it returns: the code signed in, so the number's wrong codes are cleared and the slot kept;</li>
 * <li>{@link CodeRejectedException} for a malformed or wrong code: the slot is kept;</li>
 * <li>{@link TooManyAttemptsException}, Twilio's attempt cap: the slot is kept;</li>
 * <li>anything else, including an expired code: the slot is given back.</li>
 * </ul>
 * An expired verification takes no slot, so expiry wins over the attempt cap. Past the limit, or
 * after Twilio's attempt cap has closed the verification, the call throws
 * {@link TooManyAttemptsException} without running.
 */
@PhoneRateLimited(PhoneRateLimited.Kind.CODE_CHECK)
@Interceptor
@Priority(PhoneRateLimited.PRIORITY)
public class CodeCheckLimitInterceptor {

    private final PhoneRateLimiter limiter;
    private final VerificationLedger verifications;

    @Inject
    public CodeCheckLimitInterceptor(PhoneRateLimiter limiter, VerificationLedger verifications) {
        this.limiter = limiter;
        this.verifications = verifications;
    }

    @AroundInvoke
    Object limit(InvocationContext context) throws Exception {
        Object[] arguments = context.getParameters();
        if (arguments.length != 1 || !(arguments[0] instanceof VerifyRequest verify)) {
            return context.proceed();
        }
        String phone = verify.phone();
        if (verifications.expired(phone)) {
            return context.proceed();
        }
        if (verifications.exhausted(phone)) {
            throw new TooManyAttemptsException(limiter.window());
        }
        PhoneRateLimiter.CodeCheck check = limiter.tryAcquireCodeCheck(phone);
        if (check == null) {
            verifications.exhaust(phone);
            throw new TooManyAttemptsException(limiter.window());
        }
        Object answer;
        try {
            answer = context.proceed();
        } catch (CodeRejectedException rejected) {
            if (countsAsWrongCode(rejected)) {
                check.keep();
            } else {
                check.release();
            }
            throw rejected;
        } catch (TooManyAttemptsException capped) {
            check.keep();
            throw capped;
        } catch (Throwable other) {
            check.release();
            throw other;
        }
        limiter.clearWrongCodes(phone);
        check.keep();
        return answer;
    }

    /** A malformed code and a wrong code both count. An expired code does not. */
    private static boolean countsAsWrongCode(CodeRejectedException rejected) {
        return rejected.reason() == null || VerifyFailure.WRONG_CODE.equals(rejected.reason());
    }
}
