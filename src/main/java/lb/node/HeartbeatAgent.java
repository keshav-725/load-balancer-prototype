package lb.node;

import lb.model.NodeType;
import lb.model.ServerInfo;
import lb.util.HttpUtil;
import lb.util.Threads;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Background agent that tells every orchestrator instance "I'm alive" (the "hb" arrows in the design).
 * A node whose self-check fails simply stops beating, which is how a sick-but-running app gets detected.
 */
public class HeartbeatAgent {
    private final NodeType type;
    private final Supplier<ServerInfo> self;
    private final Supplier<List<String>> orchestrators;
    private final BooleanSupplier selfCheck;
    private final long intervalMs;
    private ScheduledExecutorService scheduler;

    public HeartbeatAgent(NodeType type, Supplier<ServerInfo> self, Supplier<List<String>> orchestrators,
                          BooleanSupplier selfCheck, long intervalMs) {
        this.type = type;
        this.self = self;
        this.orchestrators = orchestrators;
        this.selfCheck = selfCheck;
        this.intervalMs = intervalMs;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Threads.daemon("hb"));
        scheduler.scheduleAtFixedRate(this::beat, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void beat() {
        try {
            if (!selfCheck.getAsBoolean()) {
                return;
            }
            ServerInfo me = self.get();
            String query = "/heartbeat?id=" + me.id() + "&type=" + type + "&host=" + me.host() + "&port=" + me.port();
            for (String orchestratorUrl : orchestrators.get()) {
                try {
                    HttpUtil.send("POST", orchestratorUrl + query, 300, Map.of());
                } catch (IOException ignored) {
                    // that orchestrator instance is down; the others still hear us
                }
            }
        } catch (RuntimeException ignored) {
            // never let an exception cancel the schedule
        }
    }
}
