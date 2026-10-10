package lb.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, versioned load-balancer configuration: backend list, health state, algorithm and timeouts.
 * Every change produces a new instance with version + 1 (built through {@link Builder}).
 */
public final class LBConfig {
    private final long version;
    private final Map<String, ServerInfo> backends;
    private final Set<String> unhealthy;
    private final AlgorithmType algorithm;
    private final int timeoutMs;

    private LBConfig(long version, Map<String, ServerInfo> backends, Set<String> unhealthy,
                     AlgorithmType algorithm, int timeoutMs) {
        this.version = version;
        this.backends = Collections.unmodifiableMap(new LinkedHashMap<>(backends));
        this.unhealthy = Collections.unmodifiableSet(new LinkedHashSet<>(unhealthy));
        this.algorithm = algorithm;
        this.timeoutMs = timeoutMs;
    }

    public static LBConfig initial(AlgorithmType algorithm, int timeoutMs) {
        return new LBConfig(0, Map.of(), Set.of(), algorithm, timeoutMs);
    }

    public long version() {
        return version;
    }

    public List<ServerInfo> backends() {
        return List.copyOf(backends.values());
    }

    public Set<String> unhealthyIds() {
        return unhealthy;
    }

    public List<ServerInfo> healthyBackends() {
        return backends.values().stream().filter(b -> !unhealthy.contains(b.id())).toList();
    }

    public AlgorithmType algorithm() {
        return algorithm;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    /** True when everything except the version number is identical (used to skip no-op updates). */
    public boolean sameContentAs(LBConfig other) {
        return algorithm == other.algorithm
                && timeoutMs == other.timeoutMs
                && backends.equals(other.backends)
                && unhealthy.equals(other.unhealthy);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("v").append(version).append(" [").append(algorithm).append(", timeout=")
                .append(timeoutMs).append("ms, backends=");
        sb.append(backends.keySet().stream().map(id -> unhealthy.contains(id) ? id + "(DOWN)" : id).toList());
        return sb.append(']').toString();
    }

    public static final class Builder {
        private final long baseVersion;
        private final Map<String, ServerInfo> backends;
        private final Set<String> unhealthy;
        private AlgorithmType algorithm;
        private int timeoutMs;

        private Builder(LBConfig base) {
            this.baseVersion = base.version;
            this.backends = new LinkedHashMap<>(base.backends);
            this.unhealthy = new LinkedHashSet<>(base.unhealthy);
            this.algorithm = base.algorithm;
            this.timeoutMs = base.timeoutMs;
        }

        public Builder addBackend(ServerInfo server) {
            backends.put(server.id(), server);
            unhealthy.remove(server.id());
            return this;
        }

        public Builder removeBackend(String id) {
            backends.remove(id);
            unhealthy.remove(id);
            return this;
        }

        public Builder markUnhealthy(String id) {
            if (backends.containsKey(id)) {
                unhealthy.add(id);
            }
            return this;
        }

        public Builder markHealthy(String id) {
            unhealthy.remove(id);
            return this;
        }

        public Builder algorithm(AlgorithmType algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** Produces the next version (base + 1). */
        public LBConfig build() {
            return new LBConfig(baseVersion + 1, backends, unhealthy, algorithm, timeoutMs);
        }
    }
}
