package lb.system;

/**
 * Tunables for the whole prototype. Real-world values are noted next to the defaults; the prototype
 * uses much shorter intervals so a full demo runs in about two minutes.
 */
public record SystemSettings(
        long heartbeatIntervalMs,   // prod: ~5s
        long heartbeatTimeoutMs,    // prod: ~3 missed beats
        long configPollIntervalMs,  // design diagram: 15s
        long orchestratorTickMs,
        long leaseTtlMs,            // leader election lease
        long scrapeIntervalMs,      // Prometheus scrape interval
        long dnsCacheTtlMs,         // client-side DNS cache
        int requestTimeoutMs,       // LB -> backend timeout
        double backendCpuPerRps,    // simulated CPU % consumed per request/second
        double scaleOutCpu,
        double scaleInCpu,
        int minBackends,
        int maxBackends,
        long scaleCooldownMs,
        long drainMs) {

    public static SystemSettings defaults() {
        return new SystemSettings(300, 1500, 2000, 400, 1500, 500, 1000, 2000, 1.2, 70, 25, 3, 5, 3000, 1500);
    }
}
