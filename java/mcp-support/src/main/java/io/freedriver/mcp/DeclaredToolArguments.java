package io.freedriver.mcp;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.quarkiverse.mcp.server.ToolManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses a {@code tools/call} that carries an argument its tool does not declare, with JSON-RPC
 * -32602 "invalid params", before the tool runs, and audits it as {@code denied}. An argument whose
 * type is an {@link OnlyDeclaredFields} record, or a collection of them, carries only that record's
 * components in each object. The callers are language models: a dropped argument would run a
 * different action than the one asked for. Each tool names this guardrail with {@code @ToolGuardrails(input = DeclaredToolArguments.class)}.
 */
@ApplicationScoped
public class DeclaredToolArguments implements ToolInputGuardrail {

    private final McpRefusals refusals;

    @Inject
    public DeclaredToolArguments(McpRefusals refusals) {
        this.refusals = refusals;
    }

    @Override
    public void apply(ToolInputContext context) {
        JsonObject arguments = context.getArguments();
        if (arguments == null) {
            return;
        }
        Set<String> declared = context.getTool().arguments().stream()
                .map(ToolManager.ToolArgument::name)
                .collect(Collectors.toSet());
        boolean undeclared = !declared.containsAll(arguments.fieldNames())
                || context.getTool().arguments().stream()
                        .anyMatch(argument -> undeclaredInside(argument.type(), arguments.getValue(argument.name())));
        if (undeclared) {
            refusals.denied(context.getTool().name(), arguments.getMap());
            throw new McpException("invalid params", JsonRpcErrorCodes.INVALID_PARAMS);
        }
    }

    /** True when an {@link OnlyDeclaredFields} record value, or one in a collection value, names a field its record does not declare. */
    public static boolean undeclaredInside(Type type, Object value) {
        if (value instanceof JsonArray array && elementType(type) instanceof Class<?> element && strict(element)) {
            Set<String> fields = components(element);
            return array.stream().anyMatch(item -> item instanceof JsonObject object
                    && !fields.containsAll(object.fieldNames()));
        }
        if (value instanceof JsonObject object && type instanceof Class<?> record && strict(record)) {
            return !components(record).containsAll(object.fieldNames());
        }
        return false;
    }

    static Type elementType(Type type) {
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw)
                && parameterized.getActualTypeArguments().length == 1) {
            return parameterized.getActualTypeArguments()[0];
        }
        if (type instanceof Class<?> array && array.isArray()) {
            return array.getComponentType();
        }
        return null;
    }

    static boolean strict(Class<?> type) {
        return type.isRecord() && type.isAnnotationPresent(OnlyDeclaredFields.class);
    }

    private static Set<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
