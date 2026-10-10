package lb.orchestrator;

import java.util.HashMap;
import java.util.Map;

public class InMemoryLeaseStore implements LeaseStore {
    private record Lease(String holder, long expiresAtMs) {}

    private final Map<String, Lease> leases = new HashMap<>();

    @Override
    public synchronized boolean tryAcquire(String key, String holder, long ttlMs) {
        long now = System.currentTimeMillis();
        Lease current = leases.get(key);
        if (current == null || current.expiresAtMs() <= now || current.holder().equals(holder)) {
            leases.put(key, new Lease(holder, now + ttlMs));
            return true;
        }
        return false;
    }

    @Override
    public synchronized void release(String key, String holder) {
        Lease current = leases.get(key);
        if (current != null && current.holder().equals(holder)) {
            leases.remove(key);
        }
    }
}
