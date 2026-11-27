package io.freedriver.architecture.fixtures.apiquality;

import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolResponse;

/**
 * The anonymous class implements an MCP filter. The holding class does not, so ToolResponse
 * stays refused.
 */
public class PlainAnonTool {
    void run() {
        new ToolFilter() {
            ToolResponse response;
        };
    }
}
