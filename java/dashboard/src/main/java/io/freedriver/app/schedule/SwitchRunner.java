package io.freedriver.app.schedule;

/** Performs one stored switch. */
interface SwitchRunner {

    void run(ScheduledSwitch row);
}
