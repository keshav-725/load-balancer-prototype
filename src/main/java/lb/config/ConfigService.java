package lb.config;

import lb.model.AlgorithmType;
import lb.model.LBConfig;
import lb.model.ServerInfo;
import lb.pubsub.PubSub;
import lb.util.Log;

import java.util.function.UnaryOperator;

/**
 * Control-plane write path: every configuration change is persisted, then pushed over pub/sub.
 * Used by the LB console (developer actions), the health monitor and the auto scaler.
 */
public class ConfigService {
    public static final String TOPIC = "lb.config.updates";

    private final ConfigRepository repository;
    private final PubSub pubSub;

    public ConfigService(ConfigRepository repository, PubSub pubSub) {
        this.repository = repository;
        this.pubSub = pubSub;
    }

    public LBConfig current() {
        return repository.load();
    }

    /** Applies a change atomically; no-op changes neither bump the version nor publish. */
    public synchronized LBConfig update(String reason, UnaryOperator<LBConfig.Builder> change) {
        LBConfig current = repository.load();
        LBConfig next = change.apply(current.toBuilder()).build();
        if (next.sameContentAs(current)) {
            return current;
        }
        repository.save(next);
        pubSub.publish(TOPIC, next);
        Log.info("config", reason + " -> " + next);
        return next;
    }

    public LBConfig addBackend(ServerInfo server) {
        return update("add " + server.id(), b -> b.addBackend(server));
    }

    public LBConfig removeBackend(String id) {
        return update("remove " + id, b -> b.removeBackend(id));
    }

    public LBConfig markUnhealthy(String id) {
        return update("mark " + id + " UNHEALTHY", b -> b.markUnhealthy(id));
    }

    public LBConfig markHealthy(String id) {
        return update("mark " + id + " HEALTHY", b -> b.markHealthy(id));
    }

    public LBConfig changeAlgorithm(AlgorithmType type) {
        return update("algorithm=" + type, b -> b.algorithm(type));
    }

    public LBConfig changeTimeout(int timeoutMs) {
        return update("timeout=" + timeoutMs + "ms", b -> b.timeoutMs(timeoutMs));
    }
}
