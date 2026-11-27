package io.freedriver.architecture.fixtures.apiquality;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** A generated REST client. The exemption is this annotation, on the holding class. */
@RegisterRestClient
public class ClientHolds {
    Callback api = id -> id;
}
