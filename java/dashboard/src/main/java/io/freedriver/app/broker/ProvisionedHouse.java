package io.freedriver.app.broker;

import java.util.UUID;

/**
 * One house the dashboard just provisioned on the broker.
 * {@code brokerSecret} is the autonomy user's password. It is not part of {@link #toString()}.
 * The dynamic-security admin secret is not in this value.
 */
public record ProvisionedHouse(UUID houseId, String autonomyUsername, String brokerSecret) {

    @Override
    public String toString() {
        return "ProvisionedHouse{houseId=" + houseId + ", autonomyUsername=" + autonomyUsername + "}";
    }
}
