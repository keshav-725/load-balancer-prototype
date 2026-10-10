package lb.orchestrator;

import com.sun.net.httpserver.HttpServer;
import lb.model.NodeType;
import lb.model.ServerInfo;
import lb.node.HttpNode;
import lb.util.HttpUtil;
import lb.util.Log;
import lb.util.Threads;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One orchestrator instance. All instances receive every heartbeat (so followers stay warm) and campaign
 * for leadership; only the leader runs the health monitor and the auto scaler.
 */
public class Orchestrator extends HttpNode {
    private final HeartbeatRegistry registry;
    private final LeaderElector elector;
    private final HealthMonitor healthMonitor;
    private final AutoScaler autoScaler;
    private final long tickMs;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Threads.daemon("tick"));
    private volatile boolean releaseLeaseOnStop = true;

    public Orchestrator(String id, HeartbeatRegistry registry, LeaderElector elector, HealthMonitor healthMonitor,
                        AutoScaler autoScaler, long tickMs) {
        super(id);
        this.registry = registry;
        this.elector = elector;
        this.healthMonitor = healthMonitor;
        this.autoScaler = autoScaler;
        this.tickMs = tickMs;
        elector.addListener(new LeaderElector.Listener() {
            @Override
            public void onElected(String candidateId) {
                Log.info(id, "became LEADER");
            }

            @Override
            public void onDemoted(String candidateId) {
                Log.info(id, "lost leadership");
            }
        });
    }

    @Override
    protected void registerHandlers(HttpServer server) {
        route(server, "/heartbeat", exchange -> {
            Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getRawQuery());
            ServerInfo node = new ServerInfo(q.get("id"), q.get("host"), Integer.parseInt(q.get("port")));
            registry.record(NodeType.valueOf(q.get("type")), node);
            HttpUtil.respond(exchange, 200, "ok\n");
        });
        route(server, "/status", exchange ->
                HttpUtil.respond(exchange, 200, id + " leader=" + elector.isLeader() + " nodes=" + registry.size() + "\n"));
    }

    @Override
    protected void onStarted() {
        elector.start();
        ticker.scheduleWithFixedDelay(this::tick, tickMs, tickMs, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onStopping() {
        ticker.shutdownNow();
        elector.stop(releaseLeaseOnStop);
    }

    private void tick() {
        if (!elector.isLeader()) {
            return;
        }
        try {
            healthMonitor.reconcile();
            autoScaler.evaluate();
        } catch (RuntimeException e) {
            Log.info(id, "control loop error: " + e);
        }
    }

    /** Hard failure: the lease is not released, so followers must wait for it to expire. */
    public void crash() {
        releaseLeaseOnStop = false;
        Log.info(id, "CRASHED");
        stop();
    }

    public boolean isLeader() {
        return elector.isLeader();
    }

    public AutoScaler autoScaler() {
        return autoScaler;
    }
}
