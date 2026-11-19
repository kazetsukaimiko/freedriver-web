package io.freedriver.daemon;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Test helpers for the {@link HttpSender} seam. */
final class TestHttp {

    private TestHttp() {
    }

    static HttpResponse<String> response(int status, String body, HttpRequest request) {
        return new TextResponse(status, body, request);
    }

    static String body(HttpRequest request) throws Exception {
        if (request.bodyPublisher().isEmpty()) {
            return "";
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                sink.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        if (!done.await(1, TimeUnit.SECONDS)) {
            throw new IllegalStateException("body did not complete");
        }
        return sink.toString(StandardCharsets.UTF_8);
    }

    private record TextResponse(int status, String text, HttpRequest request) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (left, right) -> true);
        }

        @Override
        public String body() {
            return text;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
