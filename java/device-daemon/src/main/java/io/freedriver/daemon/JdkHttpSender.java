package io.freedriver.daemon;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

final class JdkHttpSender implements HttpSender {

    private final HttpClient client;

    JdkHttpSender(HttpClient client) {
        this.client = client;
    }

    @Override
    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
