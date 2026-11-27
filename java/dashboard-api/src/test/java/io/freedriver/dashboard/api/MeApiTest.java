package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.api.SignedInUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MeApiTest {

    @Test
    void getMeIsAParameterlessJsonRoute() throws Exception {
        assertEquals("/api/me", MeApi.class.getAnnotation(Path.class).value());
        Method me = MeApi.class.getMethod("me");
        assertNull(me.getAnnotation(Path.class));
        assertNotNull(me.getAnnotation(GET.class));
        assertEquals(0, me.getParameterCount());
        assertEquals(SignedInUser.class, me.getReturnType());
        Produces produces = me.getAnnotation(Produces.class);
        if (produces == null) {
            produces = MeApi.class.getAnnotation(Produces.class);
        }
        assertEquals(List.of(MediaType.APPLICATION_JSON), List.of(produces.value()));
        assertEquals(Set.of("dashboard", "portal-admin"), Set.of(me.getAnnotation(RolesAllowed.class).value()));
        assertEquals(Set.of("200", "401", "403"), codes(me));
    }

    @Test
    void successDocumentsTheCallerAndDenialsCarryNoIdentitySchema() throws Exception {
        Method me = MeApi.class.getMethod("me");
        APIResponse ok = response(me, "200");
        assertEquals(1, ok.content().length);
        Content body = ok.content()[0];
        assertEquals(MediaType.APPLICATION_JSON, body.mediaType());
        assertEquals(SignedInUser.class, body.schema().implementation());
        assertEquals(0, response(me, "401").content().length);
        assertEquals(0, response(me, "403").content().length);
    }

    private static Set<String> codes(Method method) {
        return Arrays.stream(method.getAnnotation(APIResponses.class).value())
                .map(APIResponse::responseCode)
                .collect(Collectors.toSet());
    }

    private static APIResponse response(Method method, String code) {
        return Arrays.stream(method.getAnnotation(APIResponses.class).value())
                .filter(item -> code.equals(item.responseCode()))
                .findFirst()
                .orElseThrow();
    }
}
