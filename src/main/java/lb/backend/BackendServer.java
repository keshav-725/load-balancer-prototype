package lb.backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lb.model.NodeType;
import lb.node.HeartbeatAgent;
import lb.node.HttpNode;
import lb.system.SystemSettings;
import lb.util.HttpUtil;
import lb.util.Threads;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A fake application server. It answers /api/*, exposes /health and a Prometheus-style /metrics page,
 * and heartbeats to the orchestrators while its self-check passes.
 */
public class BackendServer extends HttpNode {
    private static final int BASE_LATENCY_MS = 8;

    private final SystemSettings settings;
    private final RateMeter rate = new RateMeter(1000);
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicLong served = new AtomicLong();
    private final HeartbeatAgent heartbeat;
    private volatile boolean healthy = true;
    private volatile int extraLatencyMs = 0;

    public BackendServer(String id, Supplier<List<String>> orchestrators, SystemSettings settings) {
        super(id);
        this.settings = settings;
        this.heartbeat = new HeartbeatAgent(NodeType.BACKEND, this::info, orchestrators,
                () -> healthy, settings.heartbeatIntervalMs());
    }

    @Override
    protected void registerHandlers(HttpServer server) {
        route(server, "/", this::handleApi);
        route(server, "/health", this::handleHealth);
        route(server, "/metrics", this::handleMetrics);
    }

    @Override
    protected void onStarted() {
        heartbeat.start();
    }

    @Override
    protected void onStopping() {
        heartbeat.stop();
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        inflight.incrementAndGet();
        rate.mark();
        try {
            Threads.sleep(BASE_LATENCY_MS + extraLatencyMs);
            served.incrementAndGet();
            exchange.getResponseHeaders().set("X-Backend-Id", id);
            HttpUtil.respond(exchange, 200, "hello from " + id + "\n");
        } finally {
            inflight.decrementAndGet();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        HttpUtil.respond(exchange, healthy ? 200 : 503, healthy ? "OK\n" : "UNHEALTHY\n");
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        double rps = rate.perSecond();
        double cpu = Math.min(100.0, 2.0 + rps * settings.backendCpuPerRps());
        double mem = 30.0 + Math.min(50.0, inflight.get() * 2.0);
        double net = rps * 0.5;
        HttpUtil.respond(exchange, 200, String.format("cpu=%.1f%nmem=%.1f%nnet=%.1f%n", cpu, mem, net));
    }

    /** Simulates an app whose self-check fails: it keeps running but stops heartbeating. */
    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }

    public void setExtraLatencyMs(int extraLatencyMs) {
        this.extraLatencyMs = extraLatencyMs;
    }

    public long servedCount() {
        return served.get();
    }
}
