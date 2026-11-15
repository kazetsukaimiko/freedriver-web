package io.freedriver.sms.security;

import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @PhoneRateLimited} on a method whose argument is not the request its interceptor limits is
 * refused, naming the method, instead of running without a limit.
 */
class PhoneRateLimitedShapeTest {

    @Test
    void the_send_limit_refuses_a_method_without_one_otp_send_request() throws Exception {
        Call call = new Call(Shapes.class.getDeclaredMethod("send", String.class), "+15555550123");
        SendLimitInterceptor interceptor = new SendLimitInterceptor(null, null, null, null, null);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> interceptor.limit(call));

        assertTrue(refused.getMessage().contains("Shapes.send"), refused.getMessage());
        assertFalse(call.proceeded);
    }

    @Test
    void the_code_check_limit_refuses_a_method_without_one_verify_request() throws Exception {
        Call call = new Call(Shapes.class.getDeclaredMethod("verify", String.class), "123456");
        CodeCheckLimitInterceptor interceptor = new CodeCheckLimitInterceptor(null, null);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> interceptor.limit(call));

        assertTrue(refused.getMessage().contains("Shapes.verify"), refused.getMessage());
        assertFalse(call.proceeded);
    }

    /** Methods whose parameter is not the request the interceptors limit. */
    static final class Shapes {
        String send(String phone) {
            return phone;
        }

        String verify(String code) {
            return code;
        }
    }

    /** One invocation with a fixed method and argument; records whether it proceeded. */
    private static final class Call implements InvocationContext {
        private final Method method;
        private Object[] parameters;
        private final Map<String, Object> data = new HashMap<>();
        boolean proceeded;

        Call(Method method, Object argument) {
            this.method = method;
            this.parameters = new Object[] {argument};
        }

        @Override
        public Object getTarget() {
            return null;
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
            return parameters;
        }

        @Override
        public void setParameters(Object[] params) {
            this.parameters = params;
        }

        @Override
        public Map<String, Object> getContextData() {
            return data;
        }

        @Override
        public Object proceed() {
            proceeded = true;
            return null;
        }
    }
}
