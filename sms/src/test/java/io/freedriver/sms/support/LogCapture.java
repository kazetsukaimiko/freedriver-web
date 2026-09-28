package io.freedriver.sms.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects formatted log lines under {@code io.freedriver.sms} while open. */
public final class LogCapture extends Handler implements AutoCloseable {

    private final Logger logger;
    private final List<String> lines = new CopyOnWriteArrayList<>();

    private LogCapture(String name) {
        this.logger = Logger.getLogger(name);
        setLevel(Level.ALL);
        logger.addHandler(this);
    }

    public static LogCapture open() {
        return new LogCapture("io.freedriver.sms");
    }

    @Override
    public void publish(LogRecord record) {
        String message = record.getMessage();
        Object[] params = record.getParameters();
        if (params != null && params.length > 0 && message != null) {
            try {
                message = String.format(message, params);
            } catch (RuntimeException ignored) {
                // keep the raw message
            }
        }
        lines.add(record.getLevel() + " " + message);
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    public String all() {
        return String.join("\n", lines);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
