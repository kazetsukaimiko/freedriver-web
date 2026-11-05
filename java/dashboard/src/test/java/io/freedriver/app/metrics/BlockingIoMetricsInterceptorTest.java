package io.freedriver.app.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingIoMetricsInterceptorTest {

    @Test
    void proceedRecordsOkMeter() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        BlockingIoMetricsInterceptor interceptor =
                new BlockingIoMetricsInterceptor(new GrafanaMetricsEmitter(registry));
        interceptor.around(new FakeContext(new IoBean(), IoBean.class.getMethod("ping"), false));
        assertEquals(
                1.0,
                registry.get("freedriver_io_blocking")
                        .tag("type", "IoBean")
                        .tag("method", "ping")
                        .tag("outcome", "ok")
                        .counter()
                        .count());
        assertTrue(registry.get("freedriver_io_blocking_ns")
                        .tag("type", "IoBean")
                        .tag("method", "ping")
                        .tag("outcome", "ok")
                        .counter()
                        .count()
                >= 0.0);
    }

    @Test
    void thrownProceedRecordsErrorMeter() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        BlockingIoMetricsInterceptor interceptor =
                new BlockingIoMetricsInterceptor(new GrafanaMetricsEmitter(registry));
        assertThrows(
                IllegalStateException.class,
                () -> interceptor.around(new FakeContext(new IoBean(), IoBean.class.getMethod("fail"), true)));
        assertEquals(
                1.0,
                registry.get("freedriver_io_blocking")
                        .tag("type", "IoBean")
                        .tag("method", "fail")
                        .tag("outcome", "error")
                        .counter()
                        .count());
    }

    @Test
    void grafanaSinkIsSkipped() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        BlockingIoMetricsInterceptor interceptor =
                new BlockingIoMetricsInterceptor(new GrafanaMetricsEmitter(registry));
        interceptor.around(new FakeContext(new SinkBean(), SinkBean.class.getMethod("emit"), false));
        assertNull(registry.find("freedriver_io_blocking").counter());
    }

    @GrafanaMetricsSink
    static final class SinkBean {
        public void emit() {}
    }

    static final class IoBean {
        public void ping() {}

        public void fail() {
            throw new IllegalStateException("io");
        }
    }

    private static final class FakeContext implements InvocationContext {
        private final Object target;
        private final Method method;
        private final boolean fail;

        FakeContext(Object target, Method method, boolean fail) {
            this.target = target;
            this.method = method;
            this.fail = fail;
        }

        @Override
        public Object getTarget() {
            return target;
        }

        @Override
        public Object getTimer() {
            return null;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Constructor<?> getConstructor() {
            return null;
        }

        @Override
        public Object[] getParameters() {
            return new Object[0];
        }

        @Override
        public void setParameters(Object[] params) {}

        @Override
        public Map<String, Object> getContextData() {
            return Map.of();
        }

        @Override
        public Object proceed() {
            if (fail) {
                throw new IllegalStateException("io");
            }
            return null;
        }
    }
}
