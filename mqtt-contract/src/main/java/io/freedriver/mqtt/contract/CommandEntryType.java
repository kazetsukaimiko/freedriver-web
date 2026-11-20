package io.freedriver.mqtt.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Kind of entry inside an {@link ApplianceCommandList}. Serialized as {@code type}. */
public enum CommandEntryType {
    /** Switch one appliance now or after a delay. */
    @JsonProperty(CommandEntryType.APPLIANCE_NAME)
    APPLIANCE,

    /** Drop one pending entry, or every pending entry for one target. */
    @JsonProperty(CommandEntryType.CANCEL_NAME)
    CANCEL;

    public static final String APPLIANCE_NAME = "appliance";
    public static final String CANCEL_NAME = "cancel";
}
