package lb.proxy;

import com.sun.net.httpserver.HttpExchange;
import lb.model.LBConfig;
import lb.model.ServerInfo;
import lb.strategy.LoadBalancingStrategy;
import lb.strategy.LoadBalancingStrategy.RequestContext;
import lb.util.HttpUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * The data-plane worker: picks a backend with the active strategy, forwards the request, and retries on
 * another backend when the chosen one is unreachable (hides the window before health checks catch up).
 */
public class ReverseProxy {
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "connection", "content-length", "host", "expect", "upgrade", "transfer-encoding", "keep-alive", "te", "trailer");

    private final String lbId;
    private final Supplier<LBConfig> config;
    private final Supplier<LoadBalancingStrategy> strategy;
    private final ConnectionTracker tracker;
    private final LongAdder retries = new LongAdder();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofMillis(500))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    public ReverseProxy(String lbId, Supplier<LBConfig> config, Supplier<LoadBalancingStrategy> strategy,
                        ConnectionTracker tracker) {
        this.lbId = lbId;
        this.config = config;
        this.strategy = strategy;
        this.tracker = tracker;
    }

    public long retries() {
        return retries.sum();
    }

    public void handle(HttpExchange exchange) throws IOException {
        LBConfig cfg = config.get();
        if (cfg == null) {
            HttpUtil.respond(exchange, 503, "load balancer has no configuration yet\n");
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        String method = exchange.getRequestMethod();
        String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
        if (clientId == null) {
            clientId = exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        RequestContext context = new RequestContext(clientId, exchange.getRequestURI().getPath());

        List<ServerInfo> candidates = new ArrayList<>(cfg.healthyBackends());
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<ServerInfo> pick = strategy.get().select(candidates, context);
            if (pick.isEmpty()) {
                break;
            }
            ServerInfo target = pick.get();
            candidates.remove(target);

            HttpResponse<byte[]> response;
            tracker.acquire(target.id());
            try {
                response = forward(exchange, target, cfg, method, body);
            } catch (IOException e) {
                lastError = e;
                if (!(isIdempotent(method) || e instanceof ConnectException)) {
                    break;
                }
                retries.increment();
                continue;
            } finally {
                tracker.release(target.id());
            }
            relay(exchange, response, target, method);
            return;
        }
        if (lastError == null) {
            HttpUtil.respond(exchange, 503, "no healthy backend available\n");
        } else {
            HttpUtil.respond(exchange, 502, "bad gateway: " + lastError + "\n");
        }
    }

    private HttpResponse<byte[]> forward(HttpExchange exchange, ServerInfo target, LBConfig cfg, String method,
                                         byte[] body) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.baseUrl() + exchange.getRequestURI()))
                .timeout(Duration.ofMillis(cfg.timeoutMs()))
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!SKIPPED_HEADERS.contains(name.toLowerCase())) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        builder.setHeader("X-Forwarded-For", exchange.getRemoteAddress().getAddress().getHostAddress());
        try {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private void relay(HttpExchange exchange, HttpResponse<byte[]> response, ServerInfo target, String method)
            throws IOException {
        response.headers().map().forEach((name, values) -> {
            if (!SKIPPED_HEADERS.contains(name.toLowerCase()) && !name.startsWith(":")) {
                values.forEach(value -> exchange.getResponseHeaders().add(name, value));
            }
        });
        exchange.getResponseHeaders().set("X-Served-By", target.id());
        exchange.getResponseHeaders().set("X-LB-Id", lbId);
        byte[] out = response.body();
        if (out.length == 0 || "HEAD".equals(method)) {
            exchange.sendResponseHeaders(response.statusCode(), -1);
        } else {
            exchange.sendResponseHeaders(response.statusCode(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        }
        exchange.close();
    }

    private static boolean isIdempotent(String method) {
        return method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
    }
}
