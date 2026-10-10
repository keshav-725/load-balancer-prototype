package lb.proxy;

import lb.strategy.ConnectionStats;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts in-flight requests per backend for one LB instance. */
public class ConnectionTracker implements ConnectionStats {
    private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

    public void acquire(String serverId) {
        active.computeIfAbsent(serverId, k -> new AtomicInteger()).incrementAndGet();
    }

    public void release(String serverId) {
        AtomicInteger counter = active.get(serverId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    @Override
    public int activeConnections(String serverId) {
        AtomicInteger counter = active.get(serverId);
        return counter == null ? 0 : counter.get();
    }
}
