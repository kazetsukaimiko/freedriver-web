package io.freedriver.app.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Grafana path: Micrometer meters that Prometheus scrapes at {@code /q/metrics}.
 * Blocking-IO interceptors must skip this type ({@link GrafanaMetricsSink}).
 */
@GrafanaMetricsSink
@ApplicationScoped
public class GrafanaMetricsEmitter {
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    @Inject
    public GrafanaMetricsEmitter(MeterRegistry registry) {
        this.registry = registry;
    }

    public void increment(String key, Map<String, Object> tags) {
        emit(new MetricsPointPayload(key, "INCREMENT", tags, null));
    }

    public void count(String key, Map<String, Object> tags) {
        emit(new MetricsPointPayload(key, "COUNT", tags, null));
    }

    public void gauge(String key, Map<String, Object> tags) {
        emit(new MetricsPointPayload(key, "GAUGE", tags, null));
    }

    public void emit(MetricsPointPayload point) {
        if (point == null) {
            return;
        }
        String name = metricName(point.key());
        Tags tags = tags(point.tags());
        double amount = numericValue(point.tags());
        switch (point.parsedKind()) {
            case INCREMENT -> Counter.builder(name).tags(tags).register(registry).increment();
            case COUNT -> Counter.builder(name).tags(tags).register(registry).increment(amount);
            case GAUGE -> setGauge(name, tags, amount);
        }
    }

    private void setGauge(String name, Tags tags, double amount) {
        String series = name + tags;
        AtomicReference<Double> holder = gauges.computeIfAbsent(series, key -> {
            AtomicReference<Double> ref = new AtomicReference<>(0.0);
            Gauge.builder(name, ref, AtomicReference::get).tags(tags).register(registry);
            return ref;
        });
        holder.set(amount);
    }

    static String metricName(String key) {
        StringBuilder out = new StringBuilder("freedriver_");
        String raw = key == null ? "" : key.strip();
        for (int i = 0; i < raw.length() && out.length() < 120; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == ':') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        if (out.length() == "freedriver_".length()) {
            out.append("metric");
        }
        return out.toString();
    }

    static Tags tags(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Tags.empty();
        }
        List<Tag> tags = new ArrayList<>();
        raw.forEach((k, v) -> {
            if (k == null || k.isBlank() || "value".equals(k)) {
                return;
            }
            String name = k.strip().replaceAll("[^a-zA-Z0-9_]", "_");
            if (name.isEmpty()) {
                return;
            }
            String value = v == null ? "" : String.valueOf(v);
            if (value.length() > 120) {
                value = value.substring(0, 120);
            }
            tags.add(Tag.of(name.toLowerCase(Locale.ROOT), value));
        });
        return Tags.of(tags);
    }

    static double numericValue(Map<String, Object> tags) {
        if (tags == null) {
            return 1.0;
        }
        Object value = tags.get("value");
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException ignored) {
                return 1.0;
            }
        }
        return 1.0;
    }
}
