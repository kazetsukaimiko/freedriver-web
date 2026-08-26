package io.freedriver.app.appliances;

/**
 * Small hook for a later live MQTT client (#40). Implementors subscribe Topic A
 * and publish Topic B, but they must use the same CDI bus mock-autonomy already
 * uses: fire {@link io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage}
 * for inbound state, observe {@link io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage}
 * for outbound commands. Do not invent a second observe/publish path.
 *
 * <p>mock-autonomy is the current adapter and is not this interface. This is
 * not a CDI bean and does not connect to a broker.
 */
public interface ApplianceTransport {
}
