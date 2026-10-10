package lb.config;

import lb.model.LBConfig;
import lb.pubsub.PubSub;
import lb.util.Log;
import lb.util.Threads;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Data-plane side of config distribution (lives inside every LB server):
 * PUSH via pub/sub for low latency, periodic POLL of the DB as a reliability backstop.
 * The newest version wins, so duplicate or out-of-order deliveries are harmless, and the last known
 * config keeps serving traffic even if the whole control plane is down.
 */
public class ConfigSyncService implements AutoCloseable {
    private final String ownerId;
    private final ConfigRepository repository;
    private final PubSub pubSub;
    private final long pollIntervalMs;
    private final AtomicReference<LBConfig> current = new AtomicReference<>();
    private final List<Consumer<LBConfig>> listeners = new CopyOnWriteArrayList<>();
    private PubSub.Subscription subscription;
    private ScheduledExecutorService poller;

    public ConfigSyncService(String ownerId, ConfigRepository repository, PubSub pubSub, long pollIntervalMs) {
        this.ownerId = ownerId;
        this.repository = repository;
        this.pubSub = pubSub;
        this.pollIntervalMs = pollIntervalMs;
    }

    public void onChange(Consumer<LBConfig> listener) {
        listeners.add(listener);
    }

    public LBConfig current() {
        return current.get();
    }

    public void start() {
        pull("BOOT");
        subscription = pubSub.subscribe(ConfigService.TOPIC, LBConfig.class, config -> apply(config, "PUSH"));
        poller = Executors.newSingleThreadScheduledExecutor(Threads.daemon("poll-" + ownerId));
        poller.scheduleWithFixedDelay(() -> pull("POLL"), pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void pull(String source) {
        try {
            apply(repository.load(), source);
        } catch (RuntimeException e) {
            // control plane unavailable: keep serving with the cached configuration
        }
    }

    private synchronized void apply(LBConfig incoming, String source) {
        LBConfig existing = current.get();
        if (existing != null && incoming.version() <= existing.version()) {
            return;
        }
        current.set(incoming);
        Log.info(ownerId, "config v" + incoming.version() + " applied via " + source);
        listeners.forEach(listener -> listener.accept(incoming));
    }

    @Override
    public void close() {
        if (subscription != null) {
            subscription.cancel();
        }
        if (poller != null) {
            poller.shutdownNow();
        }
    }
}
