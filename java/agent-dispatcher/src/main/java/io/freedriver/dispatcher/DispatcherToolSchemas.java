package io.freedriver.dispatcher;

import io.quarkiverse.mcp.server.InputSchemaGenerator;
import io.quarkiverse.mcp.server.ToolManager;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Input schemas advertised for the dispatcher tools.
 * The tool methods enforce the same limits.
 */
@Singleton
public class DispatcherToolSchemas implements InputSchemaGenerator<Map<String, Object>> {

    @Override
    public Map<String, Object> generate(ToolManager.ToolInfo tool) {
        return switch (tool.name()) {
            case "openbot_send_prompt" -> object(
                    Map.of(
                            "server", string(1, 253),
                            "agentName", string(1, 64),
                            "prompt", string(1, 16000),
                            "channel", enumOf("TEXT", "SPEECH"),
                            "on_busy", onBusy()),
                    List.of("server", "agentName", "prompt", "channel"));
            case "openbot_await_turn" -> object(
                    Map.of(
                            "jobId", uuid(),
                            "delaySeconds", integer(0, 120)),
                    List.of("jobId", "delaySeconds"));
            case "get_job" -> object(Map.of("jobId", uuid()), List.of("jobId"));
            case "list_jobs" -> object(
                    Map.of(
                            "agentName", string(1, 64),
                            "state", enumOf("queued", "in-progress", "done"),
                            "limit", integer(1, 50),
                            "cursor", string(1, 80)),
                    List.of());
            case "report_host" -> object(
                    Map.of(
                            "hostname", string(1, 253),
                            "bots", bots()),
                    List.of("hostname", "bots"));
            case "finish_job" -> object(
                    Map.of(
                            "assignmentId", uuid(),
                            "succeeded", Map.of("type", "boolean"),
                            "note", string(null, 2000)),
                    List.of("assignmentId", "succeeded"));
            default -> object(Map.of(), List.of());
        };
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> string(Integer min, int max) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        if (min != null) {
            schema.put("minLength", min);
        }
        schema.put("maxLength", max);
        return schema;
    }

    private static Map<String, Object> enumOf(String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", List.of(values));
        return schema;
    }

    private static Map<String, Object> onBusy() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", List.of("string", "null"));
        List<Object> values = new ArrayList<>();
        values.add("supersede");
        values.add("queue");
        values.add(null);
        schema.put("enum", values);
        return schema;
    }

    private static Map<String, Object> integer(int minimum, int maximum) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("minimum", minimum);
        schema.put("maximum", maximum);
        return schema;
    }

    private static Map<String, Object> uuid() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("format", "uuid");
        return schema;
    }

    private static Map<String, Object> bots() {
        Map<String, Object> bot = new LinkedHashMap<>();
        bot.put("type", "object");
        bot.put("additionalProperties", false);
        bot.put("properties", Map.of(
                "name", string(1, 64),
                "state", enumOf("idle", "busy"),
                "activity", string(null, 500)));
        bot.put("required", List.of("name", "state"));
        Map<String, Object> bots = new LinkedHashMap<>();
        bots.put("type", "array");
        bots.put("minItems", 1);
        bots.put("maxItems", 32);
        bots.put("items", bot);
        return bots;
    }
}
