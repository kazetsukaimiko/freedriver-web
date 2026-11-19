package io.freedriver.autonomymcp;

import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;

/**
 * The three appliance grants Grok can carry. Each name is a Keycloak client scope. The matching
 * realm role is the same words with underscores turned into hyphens. This class only reads the
 * verified token's {@code scope} claim. It does not parse a raw credential.
 */
public final class ApplianceGrants {

    public static final String READ = "appliance_read";

    public static final String STATE_WRITE = "appliance_state_write";

    public static final String DATA_WRITE = "appliance_data_write";

    /** Read, then state write, then data write. The exchange sends this order. */
    public static final List<String> NAMES = List.of(READ, STATE_WRITE, DATA_WRITE);

    private ApplianceGrants() {
    }

    /**
     * Appliance scopes named by {@code principal}, in {@link #NAMES} order, space-separated.
     * Empty when {@code principal} is not a {@link JsonWebToken}, when {@code scope} is not a
     * string, or when that string names none of the three. Other scope names are dropped, and
     * each of the three appears at most once.
     */
    public static String fromPrincipal(Object principal) {
        if (!(principal instanceof JsonWebToken jwt)) {
            return "";
        }
        Object claim = jwt.getClaim("scope");
        if (!(claim instanceof String text) || text.isBlank()) {
            return "";
        }
        StringBuilder chosen = new StringBuilder();
        for (String name : NAMES) {
            if (names(text, name)) {
                if (!chosen.isEmpty()) {
                    chosen.append(' ');
                }
                chosen.append(name);
            }
        }
        return chosen.toString();
    }

    private static boolean names(String text, String name) {
        for (String part : text.trim().split("\\s+")) {
            if (name.equals(part)) {
                return true;
            }
        }
        return false;
    }
}
