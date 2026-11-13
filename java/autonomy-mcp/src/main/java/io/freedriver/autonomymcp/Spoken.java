package io.freedriver.autonomymcp;

import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.McpDebugResponse;

final class Spoken {

    private Spoken() {
    }

    static String switched(String applianceName, boolean on) {
        return label(applianceName) + (on ? " turned on." : " turned off.");
    }

    static String scheduled(String applianceName, boolean on, int delaySeconds) {
        String action = on ? "turn on" : "turn off";
        return label(applianceName) + " will " + action + " in " + span(delaySeconds) + ".";
    }

    private static String span(int delaySeconds) {
        if (delaySeconds > 0 && delaySeconds % 60 == 0) {
            int minutes = delaySeconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        int seconds = Math.max(delaySeconds, 0);
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    static String home(HomeStatusResponse status) {
        if (status == null) {
            return "Home status is not available.";
        }
        String broker = status.brokerConnected() ? "The broker is connected." : "The broker is down.";
        String last = status.lastUpdated() == null || status.lastUpdated().isBlank()
                ? "There is no last state update."
                : "Last state update " + status.lastUpdated() + ".";
        String stale = status.stale() ? " The house has gone quiet." : "";
        return broker + " " + last + stale;
    }

    static String debug(McpDebugResponse debug) {
        if (debug == null) {
            return "Debug is not available.";
        }
        String health = debug.health() == null || debug.health().isBlank() ? "unknown" : debug.health();
        String category = debug.errorCategory() == null || debug.errorCategory().isBlank()
                ? "none"
                : debug.errorCategory();
        return "Health " + health + ". Recent error category " + category + ".";
    }

    static String refusal(int status) {
        return switch (status) {
            case 401, 403 -> "That was refused.";
            case 404 -> "That house is not in the list.";
            case 409 -> "That house has gone quiet.";
            case 429 -> "Too many requests. Try again in a minute.";
            default -> "The house could not be reached.";
        };
    }

    static String label(String applianceName) {
        if (applianceName == null || applianceName.isBlank()) {
            return "Appliance";
        }
        String spaced = applianceName.replace('-', ' ').replace('_', ' ').trim();
        if (spaced.isEmpty()) {
            return "Appliance";
        }
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
