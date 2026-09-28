package io.freedriver.sms.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.api.ErrorResponse;
import io.freedriver.sms.api.SendRequest;
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
 * App-wide rate limits keyed on the phone number in the request body of methods marked
 * {@link PhoneRateLimited}. Sends: per-number window, then the service-wide daily cap. Code
 * checks: per-number wrong codes, counted from the response. A body without a well-formed US
 * number is left to request validation, which refuses it with 400 and counts nothing.
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
    private final Validator validator;
    private final ObjectMapper json;

    @Inject
    public PhoneRateLimitFilter(PhoneRateLimiter limiter, DailySendCap dailyCap, Validator validator, ObjectMapper json) {
        this.limiter = limiter;
        this.dailyCap = dailyCap;
        this.validator = validator;
        this.json = json;
    }

    @Override
    public void filter(ContainerRequestContext request) throws IOException {
        PhoneRateLimited limit = limitOf(resourceInfo);
        if (limit == null) {
            return;
        }
        String phone = phoneFrom(request);
        if (phone == null) {
            return;
        }
        switch (limit.value()) {
            case SEND -> {
                if (!limiter.tryAcquireSend(phone) || !dailyCap.tryAcquire()) {
                    request.abortWith(error(429, ErrorResponse.RATE_LIMITED));
                }
            }
            case CODE_CHECK -> {
                if (limiter.codeChecksLocked(phone)) {
                    request.abortWith(error(400, ErrorResponse.INVALID_CODE));
                    return;
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

    /** Reads the body once, puts it back for the resource, and returns its well-formed US phone or null. */
    private String phoneFrom(ContainerRequestContext request) throws IOException {
        if (!request.hasEntity()) {
            return null;
        }
        byte[] body;
        try (InputStream in = request.getEntityStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        request.setEntityStream(new ByteArrayInputStream(body));
        if (body.length > MAX_BODY) {
            return null;
        }
        JsonNode phone;
        try {
            JsonNode root = json.readTree(body);
            phone = root == null ? null : root.get("phone");
        } catch (IOException e) {
            return null;
        }
        if (phone == null || !phone.isTextual()) {
            return null;
        }
        String value = phone.textValue();
        return validator.validateValue(SendRequest.class, "phone", value).isEmpty() ? value : null;
    }

    private static PhoneRateLimited limitOf(ResourceInfo info) {
        Method method = info == null ? null : info.getResourceMethod();
        return method == null ? null : method.getAnnotation(PhoneRateLimited.class);
    }

    private static Response error(int status, ErrorResponse body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
