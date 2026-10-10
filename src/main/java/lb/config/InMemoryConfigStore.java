package lb.config;

import lb.model.LBConfig;
import lb.util.Log;

public class InMemoryConfigStore implements ConfigStore {
    private final String name;
    private volatile LBConfig stored;
    private volatile boolean available = true;

    public InMemoryConfigStore(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
        Log.info("db", name + (available ? " is BACK online" : " is DOWN (simulated failure)"));
    }

    @Override
    public LBConfig read() {
        ensureUp();
        return stored;
    }

    @Override
    public void write(LBConfig config) {
        ensureUp();
        stored = config;
    }

    /** Test/demo helper that works even while the store is "down". */
    public long peekVersion() {
        LBConfig c = stored;
        return c == null ? -1 : c.version();
    }

    private void ensureUp() {
        if (!available) {
            throw new ConfigStoreException(name + " is unavailable");
        }
    }
}
