package lb.orchestrator;

import lb.config.ConfigService;
import lb.model.LBConfig;
import lb.monitoring.MetricsQuery;
import lb.orchestrator.ScalingPolicy.Context;
import lb.orchestrator.ScalingPolicy.Decision;

import java.util.OptionalDouble;

/** Leader-only loop: query metrics -> ask the policy -> act through the ASG, with a cooldown between actions. */
public class AutoScaler {
    private static final long METRICS_MAX_AGE_MS = 2000;

    private final ScalingPolicy policy;
    private final AutoScalingGroup asg;
    private final MetricsQuery metrics;
    private final ConfigService configService;
    private final long cooldownMs;
    private volatile boolean enabled;
    private long lastActionAtMs;

    public AutoScaler(ScalingPolicy policy, AutoScalingGroup asg, MetricsQuery metrics, ConfigService configService,
                      long cooldownMs) {
        this.policy = policy;
        this.asg = asg;
        this.metrics = metrics;
        this.configService = configService;
        this.cooldownMs = cooldownMs;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void evaluate() {
        long now = System.currentTimeMillis();
        if (!enabled || now - lastActionAtMs < cooldownMs) {
            return;
        }
        LBConfig config = configService.current();
        int healthy = config.healthyBackends().size();
        OptionalDouble cpu = metrics.averageCpu(METRICS_MAX_AGE_MS);
        String reason = String.format("healthy=%d avgCpu=%s", healthy,
                cpu.isPresent() ? String.format("%.0f%%", cpu.getAsDouble()) : "n/a");

        Decision decision = policy.decide(new Context(healthy, cpu));
        switch (decision) {
            case SCALE_OUT -> {
                asg.scaleOut(reason);
                lastActionAtMs = now;
            }
            case SCALE_IN -> {
                if (asg.scaleIn(reason).isPresent()) {
                    lastActionAtMs = now;
                }
            }
            case NONE -> { }
        }
    }
}
