package io.freedriver.daemon;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

interface HttpSender {

    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
}
