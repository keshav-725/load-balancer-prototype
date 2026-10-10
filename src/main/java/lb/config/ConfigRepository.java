package lb.config;

import lb.model.LBConfig;

/** Repository pattern: hides how/where the configuration is persisted. */
public interface ConfigRepository {
    LBConfig load();

    void save(LBConfig config);
}
