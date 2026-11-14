package io.freedriver.mcp;

import io.quarkiverse.mcp.server.GlobalInputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.runtime.DefaultSchemaGenerator;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Each tool's advertised input schema is the library's, plus {@code additionalProperties: false}:
 * {@link DeclaredToolArguments} refuses an undeclared argument, and the schema says so.
 */
@Singleton
public class StrictInputSchemas implements GlobalInputSchemaGenerator {

    private final DefaultSchemaGenerator library;

    @Inject
    public StrictInputSchemas(DefaultSchemaGenerator library) {
        this.library = library;
    }

    @Override
    public InputSchema generate(ToolManager.ToolInfo tool) {
        JsonObject schema = ((JsonObject) library.generate(tool).value()).copy();
        schema.put("additionalProperties", false);
        return new InputSchema() {
            @Override
            public Object value() {
                return schema;
            }

            @Override
            public String asJson() {
                return schema.encode();
            }
        };
    }
}
