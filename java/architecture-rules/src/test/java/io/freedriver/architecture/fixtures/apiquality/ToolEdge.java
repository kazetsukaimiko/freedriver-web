package io.freedriver.architecture.fixtures.apiquality;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolResponse;

/** An MCP edge: a method carries {@code @Tool} and the class uses ToolResponse. */
public class ToolEdge {
    ToolResponse response;

    @Tool
    void call() {
    }
}
