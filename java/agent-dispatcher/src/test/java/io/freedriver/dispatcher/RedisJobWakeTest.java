package io.freedriver.dispatcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisJobWakeTest {

    @Test
    void a_wake_on_one_dispatcher_reaches_a_waiter_on_another() {
        SharedBus bus = new SharedBus();
        RedisJobWake first = new RedisJobWake(bus);
        RedisJobWake second = new RedisJobWake(bus);

        first.wake();

        assertTrue(second.signals() >= 1);
        assertTrue(first.signals() >= 1);
    }

    /** In-memory stand-in for one Redis pub/sub channel. */
    private static final class SharedBus implements RedisJobWake.Bus {

        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void start() {
        }

        @Override
        public void publish() {
            for (Runnable listener : listeners) {
                listener.run();
            }
        }

        @Override
        public void subscribe(Runnable onMessage) {
            listeners.add(onMessage);
        }
    }
}
