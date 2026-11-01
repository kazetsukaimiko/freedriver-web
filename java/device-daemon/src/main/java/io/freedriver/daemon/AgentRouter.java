package io.freedriver.daemon;

import java.time.Duration;

interface AgentRouter {

    RouteResult route(String agentName, String prompt, Duration timeout);

    record RouteResult(boolean succeeded, String note) {
    }
}
