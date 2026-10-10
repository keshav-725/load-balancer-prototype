package lb.monitoring;

import lb.model.Metrics;

import java.util.Optional;
import java.util.OptionalDouble;

/** Read side of the metrics store ("Query" arrow from the orchestrator to Prometheus). */
public interface MetricsQuery {
    /** Average CPU across targets that reported within the last {@code maxAgeMs}. */
    OptionalDouble averageCpu(long maxAgeMs);

    Optional<Metrics> latest(String serverId);
}
