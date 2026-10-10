package lb.orchestrator;

import lb.model.NodeType;
import lb.model.ServerInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Last-seen table built from incoming heartbeats; each orchestrator instance keeps its own warm copy. */
public class HeartbeatRegistry {
    public record NodeRecord(String id, NodeType type, ServerInfo server, long lastSeenMs) {}

    private final Map<String, NodeRecord> nodes = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public HeartbeatRegistry() {
        this(System::currentTimeMillis);
    }

    public HeartbeatRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public void record(NodeType type, ServerInfo server) {
        nodes.put(server.id(), new NodeRecord(server.id(), type, server, clock.getAsLong()));
    }

    public Optional<NodeRecord> find(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public boolean isAlive(NodeRecord record, long timeoutMs) {
        return clock.getAsLong() - record.lastSeenMs() <= timeoutMs;
    }

    public List<NodeRecord> all(NodeType type) {
        return nodes.values().stream().filter(n -> n.type() == type).toList();
    }

    public int size() {
        return nodes.size();
    }
}
