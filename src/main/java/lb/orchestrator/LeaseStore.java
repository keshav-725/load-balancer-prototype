package lb.orchestrator;

/** Shared coordination store with TTL leases (the etcd / ZooKeeper role in leader election). */
public interface LeaseStore {
    /** Acquires or renews the lease if it is free, expired, or already held by {@code holder}. */
    boolean tryAcquire(String key, String holder, long ttlMs);

    void release(String key, String holder);
}
