package lb.strategy;

import lb.model.AlgorithmType;

/** Factory: maps the configured {@link AlgorithmType} to a concrete strategy instance. */
public final class StrategyFactory {
    private StrategyFactory() {}

    public static LoadBalancingStrategy create(AlgorithmType type, ConnectionStats stats) {
        return switch (type) {
            case ROUND_ROBIN -> new RoundRobinStrategy();
            case LEAST_CONNECTIONS -> new LeastConnectionsStrategy(stats);
            case RANDOM -> new RandomStrategy();
            case IP_HASH -> new IpHashStrategy();
        };
    }
}
