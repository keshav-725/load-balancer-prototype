package lb.demo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe collector of load-test results, with a readable summary. */
public class LoadReport {
    private final Map<String, LongAdder> byBackend = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> byLb = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> failures = new ConcurrentHashMap<>();
    private final LongAdder ok = new LongAdder();
    private final LongAdder failedOver = new LongAdder();
    private final LongAdder latencySumMs = new LongAdder();
    private final AtomicLong maxLatencyMs = new AtomicLong();

    void success(String lb, String backend, long latencyMs, boolean usedFallbackAddress) {
        ok.increment();
        byLb.computeIfAbsent(String.valueOf(lb), k -> new LongAdder()).increment();
        byBackend.computeIfAbsent(String.valueOf(backend), k -> new LongAdder()).increment();
        latencySumMs.add(latencyMs);
        maxLatencyMs.accumulateAndGet(latencyMs, Math::max);
        if (usedFallbackAddress) {
            failedOver.increment();
        }
    }

    void failure(String reason) {
        failures.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    public long okCount() {
        return ok.sum();
    }

    public long failedCount() {
        return failures.values().stream().mapToLong(LongAdder::sum).sum();
    }

    public long backendHits(String backendId) {
        LongAdder adder = byBackend.get(backendId);
        return adder == null ? 0 : adder.sum();
    }

    public String summary(String title) {
        long total = ok.sum() + failedCount();
        StringBuilder sb = new StringBuilder();
        sb.append("  ┌─ ").append(title).append('\n');
        sb.append(String.format("  │ requests=%d ok=%d failed=%d avgLatency=%dms maxLatency=%dms client-failovers=%d%n",
                total, ok.sum(), failedCount(), ok.sum() == 0 ? 0 : latencySumMs.sum() / ok.sum(),
                maxLatencyMs.get(), failedOver.sum()));
        appendDistribution(sb, "via LB     ", byLb);
        appendDistribution(sb, "to backend ", byBackend);
        if (!failures.isEmpty()) {
            sb.append("  │ failures: ");
            failures.forEach((reason, count) -> sb.append(reason).append('=').append(count.sum()).append(' '));
            sb.append('\n');
        }
        return sb.append("  └─").toString();
    }

    private void appendDistribution(StringBuilder sb, String label, Map<String, LongAdder> counts) {
        long sum = counts.values().stream().mapToLong(LongAdder::sum).sum();
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            long n = e.getValue().sum();
            double pct = sum == 0 ? 0 : 100.0 * n / sum;
            sb.append(String.format("  │ %s %-10s %5d  %5.1f%%  %s%n", label, e.getKey(), n, pct,
                    "█".repeat((int) Math.round(pct / 3.5))));
        });
    }
}
