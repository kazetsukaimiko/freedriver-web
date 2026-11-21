package io.freedriver.app.schedule;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Min;

/**
 * Keys under {@code freedriver.scheduled-switches}. {@code maxPending} has no default, so a missing
 * line fails startup. {@code poll} is mapped here so the existing poll property is not an unmapped
 * name; {@link ScheduledSwitchPoller.PollOff} still reads it.
 */
@ConfigMapping(prefix = "freedriver.scheduled-switches")
public interface ScheduledSwitchesConfig {

    @WithDefault("true")
    boolean poll();

    @Min(1)
    int maxPending();
}
