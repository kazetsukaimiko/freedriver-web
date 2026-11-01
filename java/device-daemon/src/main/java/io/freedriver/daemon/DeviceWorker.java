package io.freedriver.daemon;

interface DeviceWorker extends AutoCloseable {

    void registerOnce();

    void heartbeatOnce();

    void pollOnce();
}
