package io.freedriver.sms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.api.ErrorResponse;
import io.freedriver.sms.api.OtpSendRequest;
import io.freedriver.sms.api.SendRequest;
import io.freedriver.sms.api.SentResponse;
import io.freedriver.sms.api.VerifyRequest;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.SeedAgreement;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * App-wide rate limits keyed on the phone number in the body of methods marked
 * {@link PhoneRateLimited}, counted the same for listed and unlisted numbers.
 * <ul>
 * <li>Sends: a per-number window, past which the request throws {@link RateLimitedException}. Then the
 * service-wide daily cap, which counts only sends that reach the provider (listed numbers with an
 * agreement on file, or the seeded number's first sign-in carrying its agreement). Once the cap is reached, those sends get the same {@code sent} answer every
 * other number gets, and no provider call.</li>
 * <li>Code checks: a per-number count of rejected codes, taken from the response; past it the
 * request throws {@link RateLimitedException}.</li>
 * </ul>
 * A body that is not a valid request is left to request validation, which refuses it with 400 and
 * counts nothing.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 100)
public class PhoneRateLimitFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String PHONE_PROPERTY = PhoneRateLimitFilter.class.getName() + ".phone";
    private static final int MAX_BODY = 4096;

    @Context
    ResourceInfo resourceInfo;

    private final PhoneRateLimiter limiter;
    private final DailySendCap dailyCap;
    private final PhoneDirectory directory;
    private final ConsentLedger consents;
    private final SeedAgreement seedAgreement;
    private final Validator validator;
    private final ObjectMapper json;

    @Inject
    public PhoneRateLimitFilter(PhoneRateLimiter limiter, DailySendCap dailyCap, PhoneDirectory directory,
                                ConsentLedger consents, SeedAgreement seedAgreement, Validator validator,
                                ObjectMapper json) {
        this.limiter = limiter;
        this.dailyCap = dailyCap;
        this.directory = directory;
        this.consents = consents;
        this.seedAgreement = seedAgreement;
        this.validator = validator;
        this.json = json;
    }

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        PhoneRateLimited limit = limitOf(resourceInfo);
        if (limit == null) {
            return;
        }
        byte[] body = readBody(request);
        switch (limit.value()) {
            case SEND -> {
                OtpSendRequest send = sendRequest(body);
                if (send == null) {
                    return;
                }
                String phone = send.phone();
                if (!limiter.tryAcquireSend(phone)) {
                    throw new RateLimitedException(limiter.window());
                }
                if (reachesProvider(send) && !dailyCap.tryAcquire()) {
                    // The cap answers exactly like a send to an unlisted number: sent, and no provider call.
                    request.abortWith(Response.ok(SentResponse.otp(phone),
                            MediaType.APPLICATION_JSON_TYPE.withCharset("UTF-8")).build());
                }
            }
            case CODE_CHECK -> {
                String phone = verifyPhone(body);
                if (phone == null) {
                    return;
                }
                if (limiter.codeChecksLocked(phone)) {
                    throw new RateLimitedException(limiter.window());
                }
                request.setProperty(PHONE_PROPERTY, phone);
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!(request.getProperty(PHONE_PROPERTY) instanceof String phone)) {
            return;
        }
        if (response.getStatus() == 200) {
            limiter.clearWrongCodes(phone);
        } else if (response.getStatus() == 400 && ErrorResponse.INVALID_CODE.equals(response.getEntity())) {
            limiter.recordWrongCode(phone);
        }
    }

    /**
     * Whether this send would reach the provider: the number is on the phone list and has a
     * sign-in agreement on file, or it is the seeded number's first sign-in and the request carries
     * the agreement. Only those sends count against the daily cap. This decides counting only; the
     * sender's own agreement check still guards the provider call.
     */
    private boolean reachesProvider(OtpSendRequest send) {
        String phone = send.phone();
        return directory.find(phone).isPresent()
                && (consents.hasAgreed(phone, ConsentPurpose.SIGN_IN_CODES)
                || send.agreement() != null && seedAgreement.awaited(phone));
    }

    /** Reads the body once and puts it back for the resource. */
    private static byte[] readBody(ContainerRequestContext request) throws IOException {
        if (!request.hasEntity()) {
            return new byte[0];
        }
        byte[] body;
        try (InputStream in = request.getEntityStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        request.setEntityStream(new ByteArrayInputStream(body));
        return body;
    }

    /** A valid send request, or null. */
    private OtpSendRequest sendRequest(byte[] body) {
        SendRequest parsed = parse(body, SendRequest.class);
        return parsed instanceof OtpSendRequest otp && validator.validate(otp).isEmpty() ? otp : null;
    }

    /** The phone of a valid verify request, or null. */
    private String verifyPhone(byte[] body) {
        VerifyRequest parsed = parse(body, VerifyRequest.class);
        return parsed != null && validator.validate(parsed).isEmpty() ? parsed.phone() : null;
    }

    private <T> T parse(byte[] body, Class<T> type) {
        if (body.length == 0 || body.length > MAX_BODY) {
            return null;
        }
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            return null;
        }
    }

    /** The binding on the resource method, or on the interface method it implements. */
    private static PhoneRateLimited limitOf(ResourceInfo info) {
        Method method = info == null ? null : info.getResourceMethod();
        if (method == null) {
            return null;
        }
        PhoneRateLimited direct = method.getAnnotation(PhoneRateLimited.class);
        if (direct != null) {
            return direct;
        }
        for (Class<?> api : method.getDeclaringClass().getInterfaces()) {
            try {
                PhoneRateLimited declared = api.getMethod(method.getName(), method.getParameterTypes())
                        .getAnnotation(PhoneRateLimited.class);
                if (declared != null) {
                    return declared;
                }
            } catch (NoSuchMethodException ignored) {
                // not declared on this interface
            }
        }
        return null;
    }
}
