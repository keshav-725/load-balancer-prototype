package lb.strategy;

/** Read-only view of in-flight connections per backend (lets strategies stay decoupled from the proxy). */
public interface ConnectionStats {
    int activeConnections(String serverId);
}
