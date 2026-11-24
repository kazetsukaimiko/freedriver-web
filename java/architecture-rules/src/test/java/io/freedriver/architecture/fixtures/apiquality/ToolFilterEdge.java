package io.freedriver.architecture.fixtures.apiquality;

import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolResponse;

/** An MCP edge: it implements ToolFilter and uses ToolResponse. */
public class ToolFilterEdge implements ToolFilter {
    ToolResponse response;
}
