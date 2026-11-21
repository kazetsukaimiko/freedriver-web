package io.freedriver.keycloak.registration;

import java.util.ArrayList;
import java.util.List;

/**
 * The four URLs Keycloak itself fetches or posts to. A non-blank value is refused on every host.
 * Redirect URIs, client_uri, and logo_uri are not fields of this record.
 */
public record FetchUrls(
        String jwksUri,
        String sectorIdentifierUri,
        List<String> requestUris,
        String backchannelLogoutUri) {

    public FetchUrls {
        if (requestUris == null) {
            requestUris = List.of();
        } else {
            List<String> copy = new ArrayList<>();
            for (String uri : requestUris) {
                if (uri != null) {
                    copy.add(uri);
                }
            }
            requestUris = List.copyOf(copy);
        }
    }

    public static FetchUrls none() {
        return new FetchUrls(null, null, List.of(), null);
    }
}
