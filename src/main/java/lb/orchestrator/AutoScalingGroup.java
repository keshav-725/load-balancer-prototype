package lb.orchestrator;

import lb.config.ConfigService;
import lb.model.LBConfig;
import lb.model.ServerInfo;
import lb.util.Log;
import lb.util.Threads;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The ASG box: launches machines through a {@link ServerProvisioner} and registers them in the LB config,
 * and on scale-in removes a machine from the config first (draining) before terminating it.
 */
public class AutoScalingGroup {
    private final ServerProvisioner provisioner;
    private final ConfigService configService;
    private final long drainMs;
    private final Deque<ServerInfo> instances = new ArrayDeque<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(Threads.daemon("asg"));

    public AutoScalingGroup(ServerProvisioner provisioner, ConfigService configService, long drainMs) {
        this.provisioner = provisioner;
        this.configService = configService;
        this.drainMs = drainMs;
    }

    public synchronized ServerInfo scaleOut(String reason) {
        ServerInfo server = provisioner.launch();
        instances.addLast(server);
        Log.info("asg", "SCALE OUT (" + reason + ") -> " + server.id());
        configService.addBackend(server);
        return server;
    }

    /** Removes the newest healthy instance; returns empty when there is nothing safe to remove. */
    public synchronized Optional<ServerInfo> scaleIn(String reason) {
        LBConfig config = configService.current();
        Iterator<ServerInfo> newestFirst = instances.descendingIterator();
        while (newestFirst.hasNext()) {
            ServerInfo candidate = newestFirst.next();
            boolean healthyInConfig = config.healthyBackends().stream().anyMatch(b -> b.id().equals(candidate.id()));
            if (healthyInConfig) {
                newestFirst.remove();
                Log.info("asg", "SCALE IN (" + reason + ") -> draining " + candidate.id());
                configService.removeBackend(candidate.id());
                reaper.schedule(() -> provisioner.terminate(candidate.id()), drainMs, TimeUnit.MILLISECONDS);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public synchronized List<ServerInfo> instances() {
        return List.copyOf(instances);
    }

    public void shutdown() {
        reaper.shutdownNow();
    }
}
