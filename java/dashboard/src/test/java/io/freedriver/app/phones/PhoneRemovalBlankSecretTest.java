package io.freedriver.app.phones;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.app.invites.InviteUpstreamException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneRemovalBlankSecretTest {

    @Test
    void blank_secret_throws_before_keycloak_and_the_http_client() {
        for (String secret : new String[] {null, "", " ", "\t", " \n "}) {
            CountingHttp http = new CountingHttp(null);
            HttpSmsPhoneList sms = new HttpSmsPhoneList(http, new ObjectMapper(), "http://sms:8080", secret);
            AtomicInteger keycloak = new AtomicInteger();
            PhoneSignInDirectory directory = phone -> {
                keycloak.incrementAndGet();
                return true;
            };
            PhoneRemovalResource resource = new PhoneRemovalResource(sms, directory);
            InviteUpstreamException error = assertThrows(InviteUpstreamException.class,
                    () -> resource.remove(new PhoneRemovalResource.RemovePhone("+15555550123")),
                    "secret [" + secret + "]");
            assertEquals("sms shared secret is unset", error.getMessage());
            assertEquals(0, keycloak.get());
            assertEquals(0, http.sends());
            assertThrows(InviteUpstreamException.class, sms::list);
            assertThrows(InviteUpstreamException.class, () -> sms.remove("+15555550123"));
            assertEquals(0, http.sends());
        }
    }

    @Test
    void configured_secret_still_calls_keycloak_before_the_http_client() {
        CountingHttp http = new CountingHttp(removed());
        HttpSmsPhoneList sms = new HttpSmsPhoneList(http, new ObjectMapper(), "http://sms:8080", "secret-value");
        AtomicInteger keycloak = new AtomicInteger();
        PhoneSignInDirectory directory = phone -> {
            assertEquals(0, http.sends());
            keycloak.incrementAndGet();
            return true;
        };
        PhoneRemovalResource resource = new PhoneRemovalResource(sms, directory);
        Response response = resource.remove(new PhoneRemovalResource.RemovePhone("+15555550123"));
        try {
            assertEquals(204, response.getStatus());
        } finally {
            response.close();
        }
        assertEquals(1, keycloak.get());
        assertEquals(1, http.sends());
    }

    private static HttpResponse<String> removed() {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpRequest request() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String body() {
                return "{\"removed\":true}";
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://sms:8080/sms/phones/remove");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static final class CountingHttp extends HttpClient {
        private final HttpResponse<String> response;
        private int sends;

        private CountingHttp(HttpResponse<String> response) {
            this.response = response;
        }

        int sends() {
            return sends;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            sends++;
            if (response == null) {
                throw new AssertionError("http client was called");
            }
            @SuppressWarnings("unchecked")
            HttpResponse<T> sent = (HttpResponse<T>) response;
            return sent;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new AssertionError("http client was called");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new AssertionError("http client was called");
        }
    }
}
