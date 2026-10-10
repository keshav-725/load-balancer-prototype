package lb.orchestrator;

import lb.config.ConfigService;
import lb.dns.DnsRegistry;
import lb.model.LBConfig;
import lb.model.NodeType;
import lb.model.ServerInfo;
import lb.orchestrator.HeartbeatRegistry.NodeRecord;

import java.util.Optional;

/**
 * Leader-only reconciliation loop driven by heartbeats:
 * backends that stop beating are marked unhealthy (and healthy again when they resume),
 * and LB servers are added to / removed from DNS accordingly.
 */
public class HealthMonitor {
    private final HeartbeatRegistry registry;
    private final ConfigService configService;
    private final DnsRegistry dns;
    private final String lbDomain;
    private final long timeoutMs;

    public HealthMonitor(HeartbeatRegistry registry, ConfigService configService, DnsRegistry dns, String lbDomain,
                         long timeoutMs) {
        this.registry = registry;
        this.configService = configService;
        this.dns = dns;
        this.lbDomain = lbDomain;
        this.timeoutMs = timeoutMs;
    }

    public void reconcile() {
        reconcileBackends();
        reconcileLoadBalancers();
    }

    private void reconcileBackends() {
        LBConfig config = configService.current();
        for (ServerInfo backend : config.backends()) {
            Optional<NodeRecord> record = registry.find(backend.id());
            if (record.isEmpty()) {
                continue; // never heard from it yet (still booting)
            }
            boolean alive = registry.isAlive(record.get(), timeoutMs);
            boolean markedDown = config.unhealthyIds().contains(backend.id());
            if (!alive && !markedDown) {
                configService.markUnhealthy(backend.id());
            } else if (alive && markedDown) {
                configService.markHealthy(backend.id());
            }
        }
    }

    private void reconcileLoadBalancers() {
        for (NodeRecord lb : registry.all(NodeType.LB)) {
            String address = lb.server().address();
            if (registry.isAlive(lb, timeoutMs)) {
                dns.register(lbDomain, address);
            } else {
                dns.deregister(lbDomain, address);
            }
        }
    }
}
