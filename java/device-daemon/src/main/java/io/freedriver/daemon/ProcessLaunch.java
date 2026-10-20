package io.freedriver.daemon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProcessLaunch {

    private ProcessLaunch() {
    }

    static Map<String, String> childEnvironment(Map<String, String> parent, List<String> forbidden) {
        Map<String, String> child = new LinkedHashMap<>();
        copy(child, "PATH", parent);
        copy(child, "HOME", parent);
        String lang = parent == null ? null : parent.get("LANG");
        child.put("LANG", lang == null || lang.isBlank() ? "C.UTF-8" : lang);
        copy(child, "USER", parent);
        rejectSecrets(List.of(), child, forbidden);
        return Map.copyOf(child);
    }

    static void rejectSecrets(List<String> command, Map<String, String> env, List<String> forbidden) {
        List<String> secrets = secrets(forbidden);
        if (command != null) {
            for (String arg : command) {
                if (containsSecret(arg, secrets)) {
                    throw new IllegalArgumentException("refusing credential on the command line");
                }
            }
        }
        if (env != null) {
            for (String value : env.values()) {
                if (containsSecret(value, secrets)) {
                    throw new IllegalArgumentException("refusing credential in the child environment");
                }
            }
        }
    }

    static ProcessBuilder builder(List<String> command, Map<String, String> env, Path directory) {
        if (command == null || command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) {
            throw new IllegalArgumentException("missing command");
        }
        String exe = Path.of(command.get(0)).getFileName().toString();
        if (exe.equals("sh") || exe.equals("bash") || exe.equals("dash") || exe.equals("busybox")) {
            throw new IllegalArgumentException("refusing shell");
        }
        if (command.contains("-c")) {
            throw new IllegalArgumentException("refusing shell");
        }
        ProcessBuilder process = new ProcessBuilder(command);
        process.environment().clear();
        if (env != null) {
            process.environment().putAll(env);
        }
        if (directory != null) {
            process.directory(directory.toFile());
        }
        process.redirectErrorStream(true);
        return process;
    }

    private static void copy(Map<String, String> child, String key, Map<String, String> parent) {
        if (parent == null) {
            return;
        }
        String value = parent.get(key);
        if (value != null && !value.isBlank()) {
            child.put(key, value);
        }
    }

    private static List<String> secrets(List<String> forbidden) {
        List<String> secrets = new ArrayList<>();
        if (forbidden == null) {
            return secrets;
        }
        for (String secret : forbidden) {
            if (secret != null && secret.length() >= 8) {
                secrets.add(secret);
            }
        }
        return secrets;
    }

    private static boolean containsSecret(String value, List<String> secrets) {
        if (value == null) {
            return false;
        }
        for (String secret : secrets) {
            if (value.contains(secret)) {
                return true;
            }
        }
        return false;
    }
}
