package io.freedriver.dispatcher;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The OpenBot dispatch-jobs routine webhook. Client only. The body is the serialized JSON bytes;
 * {@link OpenBotSignature} signs exactly those bytes, and they are sent unchanged.
 */
@RegisterRestClient(configKey = "openbot-webhook")
public interface OpenBotWebhook {

    String TIMESTAMP = "x-openbot-timestamp";
    String DELIVERY_ID = "x-openbot-delivery-id";
    String SIGNATURE = "x-openbot-signature";

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response deliver(@HeaderParam(TIMESTAMP) long timestamp,
                     @HeaderParam(DELIVERY_ID) String deliveryId,
                     byte[] body);
}
