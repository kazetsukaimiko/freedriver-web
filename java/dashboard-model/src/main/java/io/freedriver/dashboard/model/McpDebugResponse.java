package io.freedriver.dashboard.model;

/** Read-only health and a coarse error category. No stack, token, or secret. */
public record McpDebugResponse(String health, String errorCategory) {
}
