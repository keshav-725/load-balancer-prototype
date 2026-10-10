package lb.model;

/** One scraped metrics sample (what Prometheus would store for a target). */
public record Metrics(double cpuPercent, double memPercent, double netKbps, long timestampMs) {}
