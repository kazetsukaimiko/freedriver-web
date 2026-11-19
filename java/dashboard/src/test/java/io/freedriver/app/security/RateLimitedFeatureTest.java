package io.freedriver.app.security;

import io.freedriver.app.appliances.TestAppliancesConfig;
import io.freedriver.dashboard.api.RateLimitBucket;
import io.freedriver.dashboard.api.RateLimited;
import io.freedriver.ratelimit.MemoryHitWindow;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitedFeatureTest {

    @Test
    void each_bucket_registers_one_filter_carrying_that_bucket() throws Exception {
        RateLimitedFeature feature = feature();
        Map<String, RateLimitBucket> routes = Map.of(
                "commands", RateLimitBucket.APPLIANCES_COMMANDS,
                "instances", RateLimitBucket.INSTANCES,
                "invites", RateLimitBucket.INVITES,
                "phones", RateLimitBucket.PHONES);
        for (var route : routes.entrySet()) {
            Registrations registrations = new Registrations();
            feature.configure(route(Routes.class, route.getKey()), registrations.context());
            assertEquals(1, registrations.components.size(), route.getKey());
            RateLimitedFilter filter = (RateLimitedFilter) registrations.components.getFirst();
            assertEquals(route.getValue(), filter.bucket());
            assertEquals(List.of(Priorities.AUTHORIZATION + 100), registrations.priorities);
        }
    }

    @Test
    void routes_in_one_bucket_share_one_filter_and_other_buckets_get_their_own() throws Exception {
        RateLimitedFeature feature = feature();
        Registrations first = new Registrations();
        Registrations second = new Registrations();
        Registrations other = new Registrations();
        feature.configure(route(Routes.class, "instances"), first.context());
        feature.configure(route(Routes.class, "instancesOpen"), second.context());
        feature.configure(route(Routes.class, "invites"), other.context());
        assertSame(first.components.getFirst(), second.components.getFirst());
        assertTrue(first.components.getFirst() != other.components.getFirst());
    }

    @Test
    void the_bucket_resolves_from_the_interface_method_and_the_class() throws Exception {
        RateLimitedFeature feature = feature();
        Registrations fromInterface = new Registrations();
        feature.configure(route(Implementation.class, "fromInterface"), fromInterface.context());
        assertEquals(RateLimitBucket.INSTANCES, ((RateLimitedFilter) fromInterface.components.getFirst()).bucket());

        Registrations fromClass = new Registrations();
        feature.configure(route(ClassBound.class, "anything"), fromClass.context());
        assertEquals(RateLimitBucket.PHONES, ((RateLimitedFilter) fromClass.components.getFirst()).bucket());
    }

    @Test
    void an_unmarked_route_registers_nothing() throws Exception {
        Registrations registrations = new Registrations();
        feature().configure(route(Routes.class, "unmarked"), registrations.context());
        assertTrue(registrations.components.isEmpty());
    }

    private static RateLimitedFeature feature() {
        RateLimitedFeature feature = new RateLimitedFeature();
        feature.limiter = new CallerRateLimiter(TestAppliancesConfig.of(Map.of(
                "rate-limit.permits", "2", "rate-limit.window", "60s")), new MemoryHitWindow());
        feature.identity = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("ada")).build();
        return feature;
    }

    private static ResourceInfo route(Class<?> clazz, String methodName) throws NoSuchMethodException {
        Method method = clazz.getMethod(methodName);
        return new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return method;
            }

            @Override
            public Class<?> getResourceClass() {
                return clazz;
            }
        };
    }

    /** Records {@code register(component, priority)}; every other call fails the test. */
    private static final class Registrations {
        final List<Object> components = new ArrayList<>();
        final List<Integer> priorities = new ArrayList<>();

        FeatureContext context() {
            return (FeatureContext) Proxy.newProxyInstance(FeatureContext.class.getClassLoader(),
                    new Class<?>[]{FeatureContext.class}, (proxy, method, args) -> {
                        if ("register".equals(method.getName()) && args.length == 2 && args[1] instanceof Integer priority) {
                            components.add(args[0]);
                            priorities.add(priority);
                            return proxy;
                        }
                        throw new AssertionError("unexpected " + method);
                    });
        }
    }

    public static class Routes {
        @RateLimited(RateLimitBucket.APPLIANCES_COMMANDS)
        public void commands() {
        }

        @RateLimited(RateLimitBucket.INSTANCES)
        public void instances() {
        }

        @RateLimited(RateLimitBucket.INSTANCES)
        public void instancesOpen() {
        }

        @RateLimited(RateLimitBucket.INVITES)
        public void invites() {
        }

        @RateLimited(RateLimitBucket.PHONES)
        public void phones() {
        }

        public void unmarked() {
        }
    }

    public interface Contract {
        @RateLimited(RateLimitBucket.INSTANCES)
        void fromInterface();
    }

    public static class Implementation implements Contract {
        @Override
        public void fromInterface() {
        }
    }

    @RateLimited(RateLimitBucket.PHONES)
    public static class ClassBound {
        public void anything() {
        }
    }
}
