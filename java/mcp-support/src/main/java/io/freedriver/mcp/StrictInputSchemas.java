package io.freedriver.mcp;

import io.quarkiverse.mcp.server.GlobalInputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.runtime.DefaultSchemaGenerator;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.lang.reflect.Type;

/**
 * Each tool's advertised input schema is the library's, plus {@code additionalProperties: false}:
 * {@link DeclaredToolArguments} refuses an undeclared argument, and the schema says so. The object
 * schema of an {@link OnlyDeclaredFields} record argument, or of the items of a list of them, says
 * so too.
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
        JsonObject properties = schema.getJsonObject("properties");
        for (ToolManager.ToolArgument argument : tool.arguments()) {
            JsonObject property = properties == null ? null : properties.getJsonObject(argument.name());
            if (property != null) {
                closeRecords(schema, property, argument.type());
            }
        }
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

    private static void closeRecords(JsonObject root, JsonObject property, Type type) {
        if (type instanceof Class<?> record && DeclaredToolArguments.strict(record)) {
            resolve(root, property).put("additionalProperties", false);
        } else if (DeclaredToolArguments.elementType(type) instanceof Class<?> element
                && DeclaredToolArguments.strict(element)
                && property.getValue("items") instanceof JsonObject items) {
            resolve(root, items).put("additionalProperties", false);
        }
    }

    /** The schema a {@code $ref} into {@code $defs} or {@code definitions} names, or the schema itself. */
    private static JsonObject resolve(JsonObject root, JsonObject schema) {
        String ref = schema.getString("$ref");
        if (ref == null || !ref.startsWith("#/")) {
            return schema;
        }
        JsonObject target = root;
        for (String part : ref.substring(2).split("/")) {
            target = target == null ? null : target.getJsonObject(part);
        }
        return target == null ? schema : target;
    }
}
