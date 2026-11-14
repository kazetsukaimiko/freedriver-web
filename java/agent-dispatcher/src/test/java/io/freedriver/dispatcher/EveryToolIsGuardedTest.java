package io.freedriver.dispatcher;

import io.freedriver.mcp.DeclaredToolArguments;
import io.quarkiverse.mcp.server.ToolGuardrails;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every registered tool refuses undeclared arguments: each carries the DeclaredToolArguments guardrail. */
@QuarkusTest
class EveryToolIsGuardedTest {

    @Test
    void every_registered_tool_carries_the_declared_arguments_guardrail() {
        // Looked up, not injected: an injected ToolManager makes the server advertise tools.listChanged.
        ToolManager tools = Arc.container().instance(ToolManager.class).get();
        List<String> unguarded = new ArrayList<>();
        int count = 0;
        for (ToolManager.ToolInfo tool : tools) {
            count++;
            Method method = tool.method().orElse(null);
            ToolGuardrails guardrails = method == null ? null : method.getAnnotation(ToolGuardrails.class);
            if (guardrails == null || !Arrays.asList(guardrails.input()).contains(DeclaredToolArguments.class)) {
                unguarded.add(tool.name());
            }
        }
        assertTrue(count > 0, "no tools registered");
        assertTrue(unguarded.isEmpty(), "tools without DeclaredToolArguments: " + unguarded);
    }
}
