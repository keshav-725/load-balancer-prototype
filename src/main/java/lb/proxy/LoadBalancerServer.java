package lb.proxy;

import com.sun.net.httpserver.HttpServer;
import lb.config.ConfigSyncService;
import lb.model.AlgorithmType;
import lb.model.LBConfig;
import lb.model.NodeType;
import lb.node.HeartbeatAgent;
import lb.node.HttpNode;
import lb.strategy.LoadBalancingStrategy;
import lb.strategy.StrategyFactory;
import lb.system.SystemSettings;
import lb.util.HttpUtil;
import lb.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * One member of the stateless LB fleet. It owns a config cache (kept fresh by {@link ConfigSyncService}),
 * the currently active strategy, and a reverse proxy; it never talks to the control plane on the request path.
 */
public class LoadBalancerServer extends HttpNode {
    private final ConfigSyncService configSync;
    private final ConnectionTracker tracker = new ConnectionTracker();
    private final ReverseProxy proxy;
    private final HeartbeatAgent heartbeat;
    private final LongAdder handled = new LongAdder();
    private volatile LoadBalancingStrategy strategy;

    public LoadBalancerServer(String id, ConfigSyncService configSync, Supplier<List<String>> orchestrators,
                              SystemSettings settings) {
        super(id);
        this.configSync = configSync;
        this.strategy = StrategyFactory.create(AlgorithmType.ROUND_ROBIN, tracker);
        this.proxy = new ReverseProxy(id, configSync::current, () -> strategy, tracker);
        this.heartbeat = new HeartbeatAgent(NodeType.LB, this::info, orchestrators, () -> true,
                settings.heartbeatIntervalMs());
    }

    @Override
    protected void onStarting() {
        configSync.onChange(config -> {
            if (strategy.type() != config.algorithm()) {
                strategy = StrategyFactory.create(config.algorithm(), tracker);
                Log.info(id, "now using " + config.algorithm());
            }
        });
        configSync.start();
    }

    @Override
    protected void registerHandlers(HttpServer server) {
        route(server, "/", exchange -> {
            handled.increment();
            proxy.handle(exchange);
        });
        route(server, "/__lb/status", exchange -> {
            LBConfig config = configSync.current();
            HttpUtil.respond(exchange, 200, id + " config=" + config + " handled=" + handled.sum() + "\n");
        });
    }

    @Override
    protected void onStarted() {
        heartbeat.start();
    }

    @Override
    protected void onStopping() {
        heartbeat.stop();
        configSync.close();
    }

    public long configVersion() {
        LBConfig config = configSync.current();
        return config == null ? -1 : config.version();
    }

    public AlgorithmType algorithm() {
        return strategy.type();
    }

    public long handledCount() {
        return handled.sum();
    }

    public long retryCount() {
        return proxy.retries();
    }
}
