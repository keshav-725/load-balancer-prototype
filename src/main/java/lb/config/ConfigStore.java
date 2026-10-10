package lb.config;

import lb.model.LBConfig;

/** One physical database node holding the configuration (the DB / replica boxes). */
public interface ConfigStore {
    String name();

    boolean isAvailable();

    /** @return the stored config, or null if nothing was ever written. */
    LBConfig read();

    void write(LBConfig config);
}
