package io.freedriver.dashboard.model;

public record HomeStatusResponse(boolean brokerConnected, String lastUpdated, boolean stale) {
}
