package lb.monitoring;

import lb.model.Metrics;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Prometheus stand-in: keeps the latest scraped sample per target. */
public class MetricsStore implements MetricsQuery {
    private final Map<String, Metrics> latest = new ConcurrentHashMap<>();

    public void record(String serverId, Metrics metrics) {
        latest.put(serverId, metrics);
    }

    public void retainOnly(Set<String> serverIds) {
        latest.keySet().retainAll(serverIds);
    }

    @Override
    public OptionalDouble averageCpu(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        return latest.values().stream()
                .filter(m -> m.timestampMs() >= cutoff)
                .mapToDouble(Metrics::cpuPercent)
                .average();
    }

    @Override
    public Optional<Metrics> latest(String serverId) {
        return Optional.ofNullable(latest.get(serverId));
    }
}
