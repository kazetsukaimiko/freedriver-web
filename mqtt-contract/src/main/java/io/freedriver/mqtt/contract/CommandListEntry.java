package io.freedriver.mqtt.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One entry of an {@link ApplianceCommandList}. The {@code type} property picks the subtype.
 * A missing or unknown type is rejected.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ApplianceCommandEntry.class, name = CommandEntryType.APPLIANCE_NAME),
        @JsonSubTypes.Type(value = CancelCommandEntry.class, name = CommandEntryType.CANCEL_NAME)
})
public sealed interface CommandListEntry permits ApplianceCommandEntry, CancelCommandEntry {

    String id();

    CommandEntryType type();
}
