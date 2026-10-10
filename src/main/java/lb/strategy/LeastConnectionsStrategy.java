package lb.strategy;

import lb.model.AlgorithmType;
import lb.model.ServerInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Picks the backend with the fewest in-flight requests; ties are rotated so no backend is favoured. */
public class LeastConnectionsStrategy implements LoadBalancingStrategy {
    private final ConnectionStats stats;
    private final AtomicLong rotation = new AtomicLong();

    public LeastConnectionsStrategy(ConnectionStats stats) {
        this.stats = stats;
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.LEAST_CONNECTIONS;
    }

    @Override
    public Optional<ServerInfo> select(List<ServerInfo> candidates, RequestContext context) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int size = candidates.size();
        int start = (int) Math.floorMod(rotation.getAndIncrement(), (long) size);
        ServerInfo best = null;
        int bestLoad = Integer.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            ServerInfo candidate = candidates.get((start + i) % size);
            int load = stats.activeConnections(candidate.id());
            if (load < bestLoad) {
                best = candidate;
                bestLoad = load;
            }
        }
        return Optional.ofNullable(best);
    }
}
