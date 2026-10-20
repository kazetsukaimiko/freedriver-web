package io.freedriver.daemon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Notes {

    static final int LIMIT = 2000;

    private Notes() {
    }

    static String forUpdate(String text, String... forbidden) {
        if (text == null) {
            return null;
        }
        List<String> secrets = new ArrayList<>();
        for (String secret : forbidden) {
            if (secret != null && secret.length() >= 8 && !secrets.contains(secret)) {
                secrets.add(secret);
            }
        }
        secrets.sort(Comparator.comparingInt(String::length).reversed());
        String cleaned = text;
        for (String secret : secrets) {
            cleaned = cleaned.replace(secret, "[redacted]");
        }
        if (cleaned.length() > LIMIT) {
            cleaned = cleaned.substring(0, LIMIT);
        }
        return cleaned;
    }
}
