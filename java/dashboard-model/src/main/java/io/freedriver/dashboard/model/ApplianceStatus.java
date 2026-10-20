package io.freedriver.dashboard.model;

/** REST switch row. MQTT uses {@code state}; HTTP keeps {@code on}. */
public record ApplianceStatus(String applianceName, boolean on) {}
