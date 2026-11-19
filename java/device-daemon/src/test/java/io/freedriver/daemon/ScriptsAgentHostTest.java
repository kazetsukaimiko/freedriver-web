package io.freedriver.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptsAgentHostTest {

    @TempDir
    Path dir;

    private final Script sender = new Script();

    @Test
    void lists_agents_with_the_scripts_token_only() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        sender.answer(200, "[{\"id\":\"agent-1\",\"name\":\"Tech Ops\",\"routines\":[]},{\"id\":\"agent-2\",\"name\":\"yuni\"}]");

        List<AgentHost.Agent> agents = host().agents();

        assertEquals(List.of(new AgentHost.Agent("agent-1", "Tech Ops"), new AgentHost.Agent("agent-2", "yuni")), agents);
        HttpRequest request = sender.requests.get(0);
        assertEquals("http://127.0.0.1:41234/v1/agents", request.uri().toString());
        assertEquals("Bearer token-one-abcdef", request.headers().firstValue("Authorization").orElseThrow());
        assertTrue(request.headers().firstValue("Origin").isEmpty());
        assertEquals(ScriptsAgentHost.REDIRECTS, java.net.http.HttpClient.Redirect.NEVER);
    }

    @Test
    void files_are_read_again_after_401() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        ScriptsAgentHost host = host();
        sender.answer(200, "[]");
        host.agents();

        files("http://127.0.0.1:50000", "token-two-abcdef");
        sender.answer(401, "{\"error\":\"unauthorized\"}");
        sender.answer(200, "[{\"id\":\"agent-1\",\"name\":\"yuni\"}]");
        assertEquals(1, host.agents().size());

        HttpRequest retried = sender.requests.get(2);
        assertEquals("http://127.0.0.1:50000/v1/agents", retried.uri().toString());
        assertEquals("Bearer token-two-abcdef", retried.headers().firstValue("Authorization").orElseThrow());
        assertEquals(List.of("token-two-abcdef"), host.forbidden());
    }

    @Test
    void files_are_read_again_after_a_refused_connection() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        ScriptsAgentHost host = host();
        sender.answer(200, "[]");
        host.agents();

        files("http://127.0.0.1:50000", "token-two-abcdef");
        sender.refuse();
        sender.answer(200, "[]");
        host.agents();
        assertEquals("http://127.0.0.1:50000/v1/agents", sender.requests.get(2).uri().toString());
    }

    @Test
    void a_missing_file_or_a_symlink_is_not_reachable() throws Exception {
        assertThrows(AgentHost.Unavailable.class, () -> host().agents());

        Path real = dir.resolve("elsewhere");
        Files.writeString(real, "token-one-abcdef");
        Files.writeString(dir.resolve("url"), "http://127.0.0.1:41234");
        Files.createSymbolicLink(dir.resolve("token"), real);
        assertThrows(AgentHost.Unavailable.class, () -> host().agents());
        assertTrue(sender.requests.isEmpty());
    }

    @Test
    void a_url_on_another_host_is_refused() {
        ScriptsAgentHost.Resolver resolver = host -> switch (host) {
            case "localhost" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            case "split.example" -> new InetAddress[] {
                    InetAddress.getByName("127.0.0.1"), InetAddress.getByName("192.0.2.10")};
            default -> {
                if (host.matches("[0-9.:]+")) {
                    yield new InetAddress[] {InetAddress.getByName(host)};
                }
                throw new UnknownHostException(host);
            }
        };
        ScriptsAgentHost.loopback("http://127.0.0.1:41234", resolver);
        ScriptsAgentHost.loopback("http://localhost:41234", resolver);
        ScriptsAgentHost.loopback("https://127.0.0.1:41234/", resolver);
        ScriptsAgentHost.loopback("http://[::1]:41234", resolver);
        for (String refused : List.of(
                "http://192.0.2.10:41234",
                "http://split.example:41234",
                "http://unknown.example:41234",
                "ftp://127.0.0.1:41234",
                "http://user:pass@127.0.0.1:41234",
                "http://127.0.0.1:41234/?x=1",
                "http://127.0.0.1:41234/#frag",
                "http://127.0.0.1:41234/v1",
                "file:///tmp/url")) {
            assertThrows(AgentHost.Unavailable.class, () -> ScriptsAgentHost.loopback(refused, resolver), refused);
        }
    }

    @Test
    void a_foreign_url_file_sends_nothing() throws Exception {
        files("http://192.0.2.10:41234", "token-one-abcdef");
        assertThrows(AgentHost.Unavailable.class, () -> host().agents());
        assertTrue(sender.requests.isEmpty());
    }

    @Test
    void the_message_carries_text_and_key_and_returns_the_delivery() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        sender.answer(202, "{\"messageId\":\"m-1\",\"deliveries\":[{\"id\":\"d-1\",\"recipientAgentId\":\"agent 1\",\"status\":\"queued\",\"position\":0}]}");

        AgentHost.SendResult result = host().send("agent 1", "key-1", "hello\nworld");

        assertEquals(new AgentHost.Sent(List.of("d-1")), result);
        HttpRequest request = sender.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1/agents/agent%201/messages", request.uri().getRawPath());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("{\"text\":\"hello\\nworld\",\"clientMessageId\":\"key-1\"}", TestHttp.body(request));
    }

    @Test
    void a_401_on_the_message_reads_the_files_and_sends_once_more() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        ScriptsAgentHost host = host();
        sender.answer(401, "{}");
        sender.answer(202, "{\"deliveries\":[{\"id\":\"d-1\"}]}");
        assertEquals(new AgentHost.Sent(List.of("d-1")), host.send("a", "k", "t"));
        assertEquals(2, sender.requests.size());
    }

    @Test
    void a_refusal_carries_the_error_text() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        for (int status : new int[] {400, 403, 404, 409, 413, 429}) {
            sender.answer(status, "{\"error\":\"local scripts are off\"}");
            AgentHost.SendResult result = host().send("a", "k", "t");
            assertEquals(new AgentHost.Refused(status, "local scripts are off"), result);
        }
    }

    @Test
    void a_message_that_may_have_been_written_is_not_sent_again() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        sender.timeout();
        assertInstanceOf(AgentHost.Lost.class, host().send("a", "k", "t"));
        assertEquals(1, sender.requests.size());

        sender.answer(500, "{\"error\":\"boom\"}");
        assertInstanceOf(AgentHost.Lost.class, host().send("a", "k", "t"));
        assertEquals(2, sender.requests.size());
    }

    @Test
    void a_message_refused_at_connect_may_be_tried_again() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        sender.refuse();
        assertThrows(AgentHost.Unavailable.class, () -> host().send("a", "k", "t"));
    }

    @Test
    void each_delivery_status_is_read() throws Exception {
        files("http://127.0.0.1:41234", "token-one-abcdef");
        ScriptsAgentHost host = host();
        for (String status : List.of("queued", "starting", "running", "completed", "failed", "interrupted", "cancelled", "paused")) {
            sender.answer(200, "{\"id\":\"d-1\",\"status\":\"" + status + "\",\"turnId\":null,\"error\":null,\"answer\":\"secret answer\"}");
            AgentHost.Delivery delivery = host.delivery("agent-1", "d-1");
            assertEquals(status, delivery.status());
            assertFalse(delivery.gone());
        }
        sender.answer(404, "{\"error\":\"not found\"}");
        assertTrue(host.delivery("agent-1", "d-1").gone());
        assertEquals("/v1/agents/agent-1/deliveries/d-1", sender.requests.get(0).uri().getRawPath());

        sender.answer(503, "");
        assertThrows(AgentHost.Unavailable.class, () -> host.delivery("agent-1", "d-1"));
    }

    @Test
    void a_segment_is_percent_encoded() {
        assertEquals("Tech%20Ops", ScriptsAgentHost.segment("Tech Ops"));
        assertEquals("a%2Fb%5Cc%25", ScriptsAgentHost.segment("a/b\\c%"));
    }

    private ScriptsAgentHost host() {
        return new ScriptsAgentHost(dir, sender, host -> new InetAddress[] {InetAddress.getByName(host)});
    }

    private void files(String url, String token) throws IOException {
        Files.writeString(dir.resolve("url"), url + "\n");
        Files.writeString(dir.resolve("token"), token + "\n");
    }

    private static final class Script implements HttpSender {
        final List<HttpRequest> requests = new ArrayList<>();
        final Deque<Object> answers = new ArrayDeque<>();

        void answer(int status, String body) {
            answers.add(new Object[] {status, body});
        }

        void refuse() {
            answers.add(new ConnectException("refused"));
        }

        void timeout() {
            answers.add(new HttpTimeoutException("timed out"));
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) throws IOException {
            requests.add(request);
            Object next = answers.poll();
            if (next instanceof IOException failure) {
                throw failure;
            }
            if (next == null) {
                throw new ConnectException("no answer scripted");
            }
            Object[] answer = (Object[]) next;
            return TestHttp.response((Integer) answer[0], (String) answer[1], request);
        }
    }
}
