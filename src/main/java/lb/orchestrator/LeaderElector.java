package lb.orchestrator;

import lb.util.Threads;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lease-based leader election. Every candidate keeps trying to acquire/renew one shared lease; whoever holds
 * it is the leader. If the leader dies without releasing, a follower takes over once the TTL expires.
 */
public class LeaderElector {

    /** Observer for leadership changes. */
    public interface Listener {
        void onElected(String candidateId);

        void onDemoted(String candidateId);
    }

    private final String candidateId;
    private final LeaseStore store;
    private final String leaseKey;
    private final long ttlMs;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean leader;
    private ScheduledExecutorService scheduler;

    public LeaderElector(String candidateId, LeaseStore store, String leaseKey, long ttlMs) {
        this.candidateId = candidateId;
        this.store = store;
        this.leaseKey = leaseKey;
        this.ttlMs = ttlMs;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public boolean isLeader() {
        return leader;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Threads.daemon("elect-" + candidateId));
        scheduler.scheduleAtFixedRate(this::campaign, 0, Math.max(50, ttlMs / 3), TimeUnit.MILLISECONDS);
    }

    /** @param releaseLease true for a graceful shutdown, false to simulate a crash (lease just expires). */
    public void stop(boolean releaseLease) {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (releaseLease) {
            store.release(leaseKey, candidateId);
        }
        leader = false;
    }

    private void campaign() {
        try {
            boolean won = store.tryAcquire(leaseKey, candidateId, ttlMs);
            if (won != leader) {
                leader = won;
                for (Listener listener : listeners) {
                    if (won) {
                        listener.onElected(candidateId);
                    } else {
                        listener.onDemoted(candidateId);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // lease store hiccup; retry next round
        }
    }
}
