package io.freedriver.daemon;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

record GrokRequest(
        String binary,
        UUID sessionId,
        String agentName,
        List<String> allow,
        String prompt,
        Path workDir) {
}
