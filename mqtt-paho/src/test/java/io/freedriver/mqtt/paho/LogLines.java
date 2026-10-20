package io.freedriver.mqtt.paho;

import io.freedriver.mqtt.MqttLog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class LogLines extends Handler implements AutoCloseable {

    private final Logger logger;
    private final Level previous;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private LogLines() {
        logger = Logger.getLogger(MqttLog.NAME);
        previous = logger.getLevel();
        setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(this);
    }

    static LogLines open() {
        return new LogLines();
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

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
        logger.setLevel(previous);
    }
}
