package io.freedriver.autonomymcp;

import io.freedriver.mcp.McpAudit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** The audit lines {@link McpAudit} writes while open. */
final class AuditLines extends Handler implements AutoCloseable {

    private final Logger logger = Logger.getLogger(McpAudit.class.getName());
    private final List<String> lines = new CopyOnWriteArrayList<>();

    private AuditLines() {
        setLevel(Level.ALL);
        logger.addHandler(this);
    }

    static AuditLines open() {
        return new AuditLines();
    }

    List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getMessage() != null) {
            lines.add(record.getMessage());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
