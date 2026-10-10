package lb.orchestrator;

import java.util.OptionalDouble;

/** Strategy pattern for scaling decisions, so CPU-based rules can be swapped for e.g. request-rate rules. */
public interface ScalingPolicy {
    enum Decision { SCALE_OUT, SCALE_IN, NONE }

    record Context(int healthyBackends, OptionalDouble avgCpu) {}

    Decision decide(Context context);
}
