package io.freedriver.app.appliances;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record Appliance(String id, String name, boolean on) {

    @JsonCreator
    public Appliance(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "on", required = true) boolean on) {
        if (!ApplianceSchemas.validId(id)) {
            throw new IllegalArgumentException("invalid appliance id");
        }
        if (!ApplianceSchemas.validName(name)) {
            throw new IllegalArgumentException("invalid appliance name");
        }
        this.id = id;
        this.name = name;
        this.on = on;
    }
}
