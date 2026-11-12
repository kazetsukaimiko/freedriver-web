package io.freedriver.dispatcher;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cross-process wake for a dispatcher wait. A publish on
 * {@code freedriver:dispatcher:job-wake} notifies every subscriber.
 */
final class RedisJobWake {

    static final String CHANNEL = "freedriver:dispatcher:job-wake";

    interface Bus {
        void start();

        void publish();

        void subscribe(Runnable onMessage);
    }

    private final Object lock = new Object();
    private final Bus bus;
    private final AtomicInteger signals = new AtomicInteger();

    RedisJobWake(String url) {
        this(new LettuceBus(url));
    }

    RedisJobWake(Bus bus) {
        this.bus = bus;
        bus.subscribe(this::onMessage);
    }

    Object lock() {
        bus.start();
        return lock;
    }

    void wake() {
        bus.start();
        onMessage();
        bus.publish();
    }

    int signals() {
        return signals.get();
    }

    private void onMessage() {
        signals.incrementAndGet();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Lettuce publish and subscribe. A down Redis leaves the local lock in place. */
    private static final class LettuceBus implements Bus {

        private final String url;
        private RedisPubSubCommands<String, String> publish;
        private boolean failed;
        private Runnable onMessage = () -> { };

        LettuceBus(String url) {
            this.url = url;
        }

        @Override
        public synchronized void start() {
            ensure();
        }

        @Override
        public synchronized void publish() {
            ensure();
            if (publish != null) {
                publish.publish(CHANNEL, "1");
            }
        }

        @Override
        public synchronized void subscribe(Runnable listener) {
            this.onMessage = listener;
        }

        private void ensure() {
            if (publish != null || failed) {
                return;
            }
            try {
                RedisURI uri = RedisURI.create(url);
                uri.setTimeout(Duration.ofMillis(500));
                RedisClient client = RedisClient.create(uri);
                StatefulRedisPubSubConnection<String, String> subscriber = client.connectPubSub();
                subscriber.addListener(new RedisPubSubAdapter<>() {
                    @Override
                    public void message(String channel, String message) {
                        onMessage.run();
                    }
                });
                subscriber.sync().subscribe(CHANNEL);
                publish = client.connectPubSub().sync();
            } catch (RuntimeException ignored) {
                failed = true;
            }
        }
    }
}
