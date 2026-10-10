package lb.orchestrator;

import lb.backend.BackendServer;
import lb.model.ServerInfo;
import lb.system.SystemSettings;
import lb.util.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Provisioner that "boots a machine" by starting a BackendServer on a fresh local port. */
public class LocalBackendProvisioner implements ServerProvisioner {
    private final Supplier<List<String>> orchestratorUrls;
    private final SystemSettings settings;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, BackendServer> servers = new ConcurrentHashMap<>();

    public LocalBackendProvisioner(Supplier<List<String>> orchestratorUrls, SystemSettings settings) {
        this.orchestratorUrls = orchestratorUrls;
        this.settings = settings;
    }

    @Override
    public ServerInfo launch() {
        String id = "backend-" + sequence.incrementAndGet();
        BackendServer server = new BackendServer(id, orchestratorUrls, settings);
        try {
            ServerInfo info = server.start();
            servers.put(id, server);
            Log.info("provision", "launched " + id + " on port " + info.port());
            return info;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void terminate(String serverId) {
        BackendServer server = servers.remove(serverId);
        if (server != null) {
            server.stop();
            Log.info("provision", "terminated " + serverId);
        }
    }

    public BackendServer get(String serverId) {
        return servers.get(serverId);
    }

    public List<BackendServer> all() {
        return new ArrayList<>(servers.values());
    }

    public void terminateAll() {
        new ArrayList<>(servers.keySet()).forEach(this::terminate);
    }
}
