package lb.strategy;

import lb.model.AlgorithmType;
import lb.model.ServerInfo;

import java.util.List;
import java.util.Optional;

/** Sticky routing: the same client id always maps to the same backend while the pool is unchanged. */
public class IpHashStrategy implements LoadBalancingStrategy {
    @Override
    public AlgorithmType type() {
        return AlgorithmType.IP_HASH;
    }

    @Override
    public Optional<ServerInfo> select(List<ServerInfo> candidates, RequestContext context) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(Math.floorMod(context.clientId().hashCode(), candidates.size())));
    }
}
