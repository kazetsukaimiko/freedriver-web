package io.freedriver.daemon;

interface RunningGrok extends AutoCloseable {

    boolean alive();

    void kill();

    int exitCode();

    String output();

    @Override
    void close();
}
