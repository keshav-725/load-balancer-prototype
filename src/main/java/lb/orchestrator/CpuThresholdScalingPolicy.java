package lb.orchestrator;

/** Scale out above a high CPU mark, in below a low mark, always staying within [min, max] healthy backends. */
public class CpuThresholdScalingPolicy implements ScalingPolicy {
    private final int minBackends;
    private final int maxBackends;
    private final double scaleOutCpu;
    private final double scaleInCpu;

    public CpuThresholdScalingPolicy(int minBackends, int maxBackends, double scaleOutCpu, double scaleInCpu) {
        this.minBackends = minBackends;
        this.maxBackends = maxBackends;
        this.scaleOutCpu = scaleOutCpu;
        this.scaleInCpu = scaleInCpu;
    }

    @Override
    public Decision decide(Context context) {
        int healthy = context.healthyBackends();
        if (healthy < minBackends) {
            return Decision.SCALE_OUT; // self-healing: replace lost capacity regardless of load
        }
        if (context.avgCpu().isEmpty()) {
            return Decision.NONE;
        }
        double cpu = context.avgCpu().getAsDouble();
        if (cpu > scaleOutCpu && healthy < maxBackends) {
            return Decision.SCALE_OUT;
        }
        if (cpu < scaleInCpu && healthy > minBackends) {
            return Decision.SCALE_IN;
        }
        return Decision.NONE;
    }
}
