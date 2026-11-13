package io.freedriver.dispatcher;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/** The webhook answered 400 or more. */
final class WebhookRefused extends RuntimeException {

    private final int status;

    WebhookRefused(int status) {
        super("webhook answered " + status, null, false, false);
        this.status = status;
    }

    int status() {
        return status;
    }

    /** Maps a webhook status of 400 or more to {@link WebhookRefused}. The body is not read. */
    static final class Mapper implements ResponseExceptionMapper<WebhookRefused> {
        @Override
        public WebhookRefused toThrowable(Response response) {
            return new WebhookRefused(response.getStatus());
        }
    }
}
