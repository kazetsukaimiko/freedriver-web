package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttLog;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects {@code io.freedriver.mqtt} lines while open. */
final class MqttLogCapture extends Handler implements AutoCloseable {

    private final Logger logger;
    private final Level previous;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private MqttLogCapture() {
        logger = Logger.getLogger(MqttLog.NAME);
        previous = logger.getLevel();
        setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(this);
    }

    static MqttLogCapture open() {
        return new MqttLogCapture();
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getMessage() != null) {
            records.add(record);
        }
    }

    String all() {
        StringBuilder out = new StringBuilder();
        for (LogRecord record : records) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(record.getMessage());
        }
        return out.toString();
    }

    String find(String part) {
        for (LogRecord record : records) {
            if (record.getMessage().contains(part)) {
                return record.getMessage();
            }
        }
        throw new AssertionError("missing " + part + " in\n" + all());
    }

    String await(String part, long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            for (LogRecord record : records) {
                if (record.getMessage().contains(part)) {
                    return record.getMessage();
                }
            }
            Thread.sleep(15);
        }
        throw new AssertionError("missing " + part + " in\n" + all());
    }

    int levelOf(String part) {
        for (LogRecord record : records) {
            if (record.getMessage().contains(part)) {
                return record.getLevel().intValue();
            }
        }
        throw new AssertionError("missing " + part + " in\n" + all());
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
        logger.setLevel(previous);
    }
}
