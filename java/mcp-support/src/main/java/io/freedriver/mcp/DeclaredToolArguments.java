package io.freedriver.mcp;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.quarkiverse.mcp.server.ToolManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses a {@code tools/call} that carries an argument its tool does not declare, with JSON-RPC
 * -32602 "invalid params", before the tool runs, and audits it as {@code denied}. The callers are
 * language models: a dropped argument would run a different action than the one asked for. Each
 * tool names this guardrail with {@code @ToolGuardrails(input = DeclaredToolArguments.class)}.
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
        if (context.getArguments() == null) {
            return;
        }
        Set<String> declared = context.getTool().arguments().stream()
                .map(ToolManager.ToolArgument::name)
                .collect(Collectors.toSet());
        if (!declared.containsAll(context.getArguments().fieldNames())) {
            refusals.denied(context.getTool().name(), context.getArguments().getMap());
            throw new McpException("invalid params", JsonRpcErrorCodes.INVALID_PARAMS);
        }
    }
}
