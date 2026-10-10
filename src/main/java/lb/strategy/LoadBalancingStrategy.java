package lb.strategy;

import lb.model.AlgorithmType;
import lb.model.ServerInfo;

import java.util.List;
import java.util.Optional;

/** Strategy pattern: how a load balancer picks one backend out of the candidates. */
public interface LoadBalancingStrategy {

    record RequestContext(String clientId, String path) {}

    AlgorithmType type();

    Optional<ServerInfo> select(List<ServerInfo> candidates, RequestContext context);
}
