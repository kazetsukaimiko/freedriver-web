package io.freedriver.mcp;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.validation.ConstraintViolationException;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * Audits a tool call refused for its role ({@code @RolesAllowed}) or by Bean Validation, as
 * {@code denied}, then lets the refusal through unchanged. It runs outside both checks: its priority
 * is lower than the security and validation interceptors'. A value the library cannot convert, or a
 * missing argument, is refused by the library before the tool bean and does not reach this interceptor.
 */
@AuditedRefusals
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE)
public class RefusalAuditInterceptor {

    @Inject
    McpRefusals refusals;

    @AroundInvoke
    Object audit(InvocationContext invocation) throws Exception {
        try {
            return invocation.proceed();
        } catch (ForbiddenException | UnauthorizedException | ConstraintViolationException refused) {
            deny(invocation);
            throw refused;
        } catch (McpException refused) {
            // The MCP validator extension turns a constraint violation into invalid params inside this interceptor.
            if (refused.getJsonRpcErrorCode() == JsonRpcErrorCodes.INVALID_PARAMS) {
                deny(invocation);
            }
            throw refused;
        }
    }

    private void deny(InvocationContext invocation) {
        Method method = invocation.getMethod();
        Tool tool = method.getAnnotation(Tool.class);
        if (tool != null) {
            refusals.denied(tool.name().isEmpty() ? method.getName() : tool.name(),
                    arguments(method, invocation.getParameters()));
        }
    }

    private static Map<String, Object> arguments(Method method, Object[] values) {
        Map<String, Object> named = new HashMap<>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length && values != null && i < values.length; i++) {
            ToolArg arg = parameters[i].getAnnotation(ToolArg.class);
            String name = arg != null && !arg.name().isEmpty() ? arg.name() : parameters[i].getName();
            named.put(name, values[i]);
        }
        return named;
    }
}
