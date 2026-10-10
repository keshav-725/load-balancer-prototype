package lb.demo;

import lb.dns.DnsClient;
import lb.util.HttpUtil;
import lb.util.Threads;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plays the part of end users: resolves the LB domain through DNS (with caching), spreads requests over the
 * returned LB addresses, and falls back to the next address when one is unreachable (like a browser would).
 */
public class LoadGenerator {
    private final DnsClient dns;
    private final String domain;

    public LoadGenerator(DnsClient dns, String domain) {
        this.dns = dns;
        this.domain = domain;
    }

    public Run start(int requestsPerSecond) {
        Run run = new Run(requestsPerSecond);
        run.begin();
        return run;
    }

    public LoadReport run(int requestsPerSecond, long durationMs) {
        Run run = start(requestsPerSecond);
        Threads.sleep(durationMs);
        return run.stop();
    }

    public final class Run {
        private final AtomicInteger rps;
        private final LoadReport report = new LoadReport();
        private final AtomicInteger inflight = new AtomicInteger();
        private final AtomicLong roundRobin = new AtomicLong();
        private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(Threads.daemon("load"));
        private double credit;

        private Run(int rps) {
            this.rps = new AtomicInteger(rps);
        }

        private void begin() {
            ticker.scheduleAtFixedRate(this::tick, 0, 100, TimeUnit.MILLISECONDS);
        }

        private void tick() {
            credit += rps.get() / 10.0;
            int n = (int) credit;
            credit -= n;
            for (int i = 0; i < n; i++) {
                inflight.incrementAndGet();
                Thread.startVirtualThread(() -> {
                    try {
                        fire();
                    } finally {
                        inflight.decrementAndGet();
                    }
                });
            }
        }

        private void fire() {
            long started = System.nanoTime();
            List<String> addresses = dns.resolve(domain);
            if (addresses.isEmpty()) {
                report.failure("dns");
                return;
            }
            int first = (int) Math.floorMod(roundRobin.getAndIncrement(), (long) addresses.size());
            Map<String, String> headers = Map.of("X-Client-Id", "user-" + ThreadLocalRandom.current().nextInt(25));
            for (int i = 0; i < addresses.size(); i++) {
                String address = addresses.get((first + i) % addresses.size());
                try {
                    HttpUtil.Result result = HttpUtil.send("GET", "http://" + address + "/api/hello", 3000, headers);
                    if (result.ok()) {
                        report.success(result.header("X-LB-Id"), result.header("X-Served-By"),
                                (System.nanoTime() - started) / 1_000_000, i > 0);
                    } else {
                        report.failure("http-" + result.status());
                    }
                    return;
                } catch (IOException e) {
                    // that LB address is unreachable: try the next one from the DNS answer
                }
            }
            report.failure("unreachable");
        }

        public void setRps(int value) {
            rps.set(value);
        }

        public LoadReport stop() {
            ticker.shutdownNow();
            long deadline = System.currentTimeMillis() + 5000;
            while (inflight.get() > 0 && System.currentTimeMillis() < deadline) {
                Threads.sleep(20);
            }
            return report;
        }
    }
}
