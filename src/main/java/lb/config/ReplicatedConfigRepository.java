package lb.config;

import lb.model.LBConfig;
import lb.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Primary + replica(s) with transparent failover:
 * writes go to every available store, reads return the newest version found and repair stale stores
 * (so a recovered primary catches up automatically).
 */
public class ReplicatedConfigRepository implements ConfigRepository {
    private final List<ConfigStore> stores;

    public ReplicatedConfigRepository(List<ConfigStore> stores) {
        this.stores = List.copyOf(stores);
    }

    @Override
    public synchronized LBConfig load() {
        Map<ConfigStore, LBConfig> reads = new LinkedHashMap<>();
        LBConfig best = null;
        for (ConfigStore store : stores) {
            if (!store.isAvailable()) {
                continue;
            }
            try {
                LBConfig config = store.read();
                reads.put(store, config);
                if (config != null && (best == null || config.version() > best.version())) {
                    best = config;
                }
            } catch (ConfigStoreException ignored) {
                // went down between the availability check and the read
            }
        }
        if (reads.isEmpty()) {
            throw new ConfigStoreException("all config stores are down");
        }
        if (best == null) {
            throw new ConfigStoreException("no configuration stored yet");
        }
        for (Map.Entry<ConfigStore, LBConfig> read : reads.entrySet()) {
            LBConfig seen = read.getValue();
            if (seen == null || seen.version() < best.version()) {
                try {
                    read.getKey().write(best);
                    Log.info("db", "read-repair: " + read.getKey().name() + " caught up to v" + best.version());
                } catch (ConfigStoreException ignored) {
                    // will be repaired on a later read
                }
            }
        }
        return best;
    }

    @Override
    public synchronized void save(LBConfig config) {
        int written = 0;
        for (ConfigStore store : stores) {
            if (!store.isAvailable()) {
                continue;
            }
            try {
                store.write(config);
                written++;
            } catch (ConfigStoreException ignored) {
                // skip, the next successful read repairs it
            }
        }
        if (written == 0) {
            throw new ConfigStoreException("all config stores are down");
        }
    }
}
