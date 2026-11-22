package io.freedriver.app.instances;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The Keycloak user stage failed. The HTTP body is cause {@code broker}.
 * {@link #reason()} and {@link #status()} tell the house-list log which line to write.
 * The message stays {@code broker refused} so a log of the exception text carries neither
 * a response body nor a token.
 */
public class BrokerRefusedException extends RuntimeException {

    /** Why the house list or a user call stopped. */
    public enum Reason {
        NOT_CONFIGURED("not-configured"),
        GROUP_MISSING("group-missing"),
        /** An HTTP error. The log word stays {@code list-failed} and the status is separate. */
        HTTP("list-failed"),
        TRANSPORT("transport");

        private final String word;

        Reason(String word) {
            this.word = word;
        }

        /** The {@code reason=} word {@code HouseMqttLog} writes. */
        public String word() {
            return word;
        }
    }

    private final Reason reason;
    private final OptionalInt status;

    public BrokerRefusedException() {
        this(null, OptionalInt.empty());
    }

    private BrokerRefusedException(Reason reason, OptionalInt status) {
        super("broker refused");
        this.reason = reason;
        this.status = status == null ? OptionalInt.empty() : status;
    }

    public static BrokerRefusedException notConfigured() {
        return new BrokerRefusedException(Reason.NOT_CONFIGURED, OptionalInt.empty());
    }

    public static BrokerRefusedException groupMissing() {
        return new BrokerRefusedException(Reason.GROUP_MISSING, OptionalInt.empty());
    }

    public static BrokerRefusedException http(int status) {
        return new BrokerRefusedException(Reason.HTTP, OptionalInt.of(status));
    }

    public static BrokerRefusedException transport() {
        return new BrokerRefusedException(Reason.TRANSPORT, OptionalInt.empty());
    }

    public Optional<Reason> reason() {
        return Optional.ofNullable(reason);
    }

    public OptionalInt status() {
        return status;
    }

    /** Word for {@code HouseMqttLog.instancesRefreshFailed}. Empty reason is {@code list-failed}. */
    public String logReason() {
        return reason == null ? "list-failed" : reason.word();
    }
}
