package lb.util;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Thin helpers around the JDK HTTP client/server so components stay free of boilerplate. */
public final class HttpUtil {

    public record Result(int status, String body, HttpHeaders headers) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofMillis(800))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private HttpUtil() {}

    public static Result get(String url, int timeoutMs) throws IOException {
        return send("GET", url, timeoutMs, Map.of());
    }

    public static Result send(String method, String url, int timeoutMs, Map<String, String> headers)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);
        try {
            HttpResponse<String> r = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(r.statusCode(), r.body(), r.headers());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(decode(pair), "");
            } else {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
