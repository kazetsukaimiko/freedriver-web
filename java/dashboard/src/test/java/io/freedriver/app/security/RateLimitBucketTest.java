package io.freedriver.app.security;

import io.freedriver.app.instances.InstancesApi;
import io.freedriver.app.invites.InviteResource;
import io.freedriver.app.phones.PhoneRemovalResource;
import io.freedriver.dashboard.api.AppliancesApi;
import io.freedriver.dashboard.api.RateLimitBucket;
import io.freedriver.dashboard.api.RateLimited;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RateLimitBucketTest {

    @Test
    void keys_are_the_four_stored_segments() {
        assertEquals(EnumSet.of(
                RateLimitBucket.APPLIANCES_COMMANDS,
                RateLimitBucket.INSTANCES,
                RateLimitBucket.INVITES,
                RateLimitBucket.PHONES), EnumSet.allOf(RateLimitBucket.class));
        assertEquals("appliances.commands", RateLimitBucket.APPLIANCES_COMMANDS.key());
        assertEquals("instances", RateLimitBucket.INSTANCES.key());
        assertEquals("invites", RateLimitBucket.INVITES.key());
        assertEquals("phones", RateLimitBucket.PHONES.key());
        for (RateLimitBucket bucket : RateLimitBucket.values()) {
            assertFalse(bucket.key().contains(":"), bucket.name());
            assertEquals(bucket.key() + ":ada", CallerRateLimiter.key(bucket, "ada"));
        }
    }

    @Test
    void production_routes_keep_their_buckets() throws Exception {
        Map<Class<?>, Map<String, RateLimitBucket>> routes = Map.of(
                AppliancesApi.class, Map.of("command", RateLimitBucket.APPLIANCES_COMMANDS),
                InstancesApi.class, Map.of(
                        "create", RateLimitBucket.INSTANCES,
                        "open", RateLimitBucket.INSTANCES),
                InviteResource.class, Map.of("create", RateLimitBucket.INVITES),
                PhoneRemovalResource.class, Map.of("remove", RateLimitBucket.PHONES));
        for (var type : routes.entrySet()) {
            for (Method method : type.getKey().getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                RateLimited mark = method.getAnnotation(RateLimited.class);
                RateLimitBucket expected = type.getValue().get(method.getName());
                if (expected == null) {
                    assertNull(mark, type.getKey().getSimpleName() + "." + method.getName());
                } else {
                    assertEquals(expected, mark.value(), type.getKey().getSimpleName() + "." + method.getName());
                }
            }
        }
        assertNull(InstancesApi.class.getMethod("list").getAnnotation(RateLimited.class));
    }
}
