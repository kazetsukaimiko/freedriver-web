package io.freedriver.daemon;

import java.util.List;

/**
 * The program that runs the agents on this host. A call that cannot reach it throws
 * {@link Unavailable}; nothing about a job is decided from that.
 */
interface AgentHost {

    /** The agents that accept messages from this connector. */
    List<Agent> agents();

    /** Puts one message into the agent's queue, under a retry key. */
    SendResult send(String agentId, String clientMessageId, String text);

    /** The state of one delivery; {@link Delivery#gone()} when the host answers that it is unknown. */
    Delivery delivery(String agentId, String deliveryId);

    /** Values a note must never carry, such as the host's own token. */
    List<String> forbidden();

    record Agent(String id, String name) {
    }

    record Delivery(String status, boolean gone) {

        static Delivery of(String status) {
            return new Delivery(status, false);
        }

        static Delivery missing() {
            return new Delivery(null, true);
        }
    }

    sealed interface SendResult {
    }

    /** The host accepted the message; the ids of its deliveries, in order. */
    record Sent(List<String> deliveryIds) implements SendResult {
        public Sent {
            deliveryIds = List.copyOf(deliveryIds);
        }
    }

    /** The host refused the message with a 4xx and this error text. */
    record Refused(int status, String error) implements SendResult {
    }

    /** The message was written and no receipt came back. */
    record Lost(String reason) implements SendResult {
    }

    final class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message);
        }
    }
}
