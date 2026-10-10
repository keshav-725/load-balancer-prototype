package lb.strategy;

import lb.model.AlgorithmType;
import lb.model.ServerInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class RoundRobinStrategy implements LoadBalancingStrategy {
    private final AtomicLong counter = new AtomicLong();

    @Override
    public AlgorithmType type() {
        return AlgorithmType.ROUND_ROBIN;
    }

    @Override
    public Optional<ServerInfo> select(List<ServerInfo> candidates, RequestContext context) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = (int) Math.floorMod(counter.getAndIncrement(), (long) candidates.size());
        return Optional.of(candidates.get(index));
    }
}
