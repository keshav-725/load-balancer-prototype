package lb.monitoring;

import lb.model.Metrics;
import lb.model.ServerInfo;
import lb.util.HttpUtil;
import lb.util.Threads;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Pull-based scraper: every interval it GETs /metrics from each target and stores the sample. */
public class MetricsScraper {
    private final Supplier<List<ServerInfo>> targets;
    private final MetricsStore store;
    private final long intervalMs;
    private ScheduledExecutorService scheduler;

    public MetricsScraper(Supplier<List<ServerInfo>> targets, MetricsStore store, long intervalMs) {
        this.targets = targets;
        this.store = store;
        this.intervalMs = intervalMs;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Threads.daemon("prometheus"));
        scheduler.scheduleWithFixedDelay(this::scrapeAll, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void scrapeAll() {
        try {
            List<ServerInfo> current = targets.get();
            store.retainOnly(current.stream().map(ServerInfo::id).collect(Collectors.toSet()));
            current.forEach(this::scrape);
        } catch (RuntimeException ignored) {
            // config store briefly unavailable; try again next interval
        }
    }

    private void scrape(ServerInfo target) {
        try {
            HttpUtil.Result result = HttpUtil.get(target.baseUrl() + "/metrics", 300);
            if (!result.ok()) {
                return;
            }
            Map<String, Double> values = new HashMap<>();
            for (String line : result.body().split("\n")) {
                String[] kv = line.split("=");
                if (kv.length == 2) {
                    values.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
                }
            }
            store.record(target.id(), new Metrics(values.getOrDefault("cpu", 0.0), values.getOrDefault("mem", 0.0),
                    values.getOrDefault("net", 0.0), System.currentTimeMillis()));
        } catch (IOException | NumberFormatException ignored) {
            // target down: no sample, it ages out of the averages
        }
    }
}
