package lb.strategy;

import lb.model.AlgorithmType;
import lb.model.ServerInfo;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class RandomStrategy implements LoadBalancingStrategy {
    @Override
    public AlgorithmType type() {
        return AlgorithmType.RANDOM;
    }

    @Override
    public Optional<ServerInfo> select(List<ServerInfo> candidates, RequestContext context) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }
}
