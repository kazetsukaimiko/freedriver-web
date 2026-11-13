package io.freedriver.app.schedule;

import java.time.Instant;
import java.util.List;

/** The Mongo collection that holds a switch until it is due. */
public interface ScheduledSwitches {

    void insert(ScheduledSwitch row);

    /** Due rows, earliest first. */
    List<ScheduledSwitch> due(Instant now);

    void delete(String id);
}
