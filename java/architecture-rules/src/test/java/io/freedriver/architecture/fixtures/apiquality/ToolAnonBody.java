package io.freedriver.architecture.fixtures.apiquality;

import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolResponse;

/** An MCP edge whose ToolResponse is used only inside an anonymous class. */
public class ToolAnonBody implements ToolFilter {
    void hidden() {
        new Runnable() {
            ToolResponse response;

            public void run() {
            }
        }.run();
    }
}
