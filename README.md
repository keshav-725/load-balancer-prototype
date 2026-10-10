# Load Balancer System: Java Prototype

A runnable, dependency-free (JDK 21 only) prototype of the load balancer system-design diagram.
Every component is a **real HTTP server on its own local port**: backends, LB fleet, DNS, orchestrators, console.
There are no external calls and no libraries.

```
./run.sh        # compiles with javac and runs the scripted demo (~50 s)
```

## Mapping the diagram to code

| Diagram box | Class(es) | Package |
|---|---|---|
| CoreDNS (private infra) | `DnsRegistry`, `DnsServer`, `DnsClient` (TTL cache) | `lb.dns` |
| LB Servers (stateless fleet) | `LoadBalancerServer`, `ReverseProxy`, `ConnectionTracker` | `lb.proxy` |
| Algorithm (config) | `LoadBalancingStrategy` + `RoundRobin`, `LeastConnections`, `Random`, `IpHash`, `StrategyFactory` | `lb.strategy` |
| Backend servers (`/health`, metrics, heartbeat) | `BackendServer`, `RateMeter` | `lb.backend` |
| LB Console (developer) | `LBConsoleServer` (HTTP API) | `lb.console` |
| DB + replica + failover | `ConfigStore`, `InMemoryConfigStore`, `ReplicatedConfigRepository` | `lb.config` |
| Redis as pub/sub (PUSH) | `PubSub`, `InMemoryPubSub` | `lb.pubsub` |
| PULL (poll) | `ConfigSyncService` (push + poll, newest version wins) | `lb.config` |
| Orchestrator | `Orchestrator`, `HealthMonitor`, `HeartbeatRegistry` | `lb.orchestrator` |
| Leader election | `LeaderElector`, `LeaseStore`, `InMemoryLeaseStore` | `lb.orchestrator` |
| Prometheus | `MetricsScraper` (pull), `MetricsStore`, `MetricsQuery` | `lb.monitoring` |
| ASG | `AutoScalingGroup`, `AutoScaler`, `ScalingPolicy`, `CpuThresholdScalingPolicy`, `ServerProvisioner` | `lb.orchestrator` |
| Whole system wiring | `LoadBalancerSystem` (facade + builder) | `lb.system` |
| End users | `LoadGenerator`, `LoadReport` | `lb.demo` |

## Design patterns used

- **Strategy**: `LoadBalancingStrategy` (algorithms) and `ScalingPolicy` (scaling rules), swappable at runtime.
- **Factory**: `StrategyFactory` turns the configured `AlgorithmType` into a strategy.
- **Template Method**: `HttpNode` owns start/stop and routing; subclasses declare routes and lifecycle hooks.
- **Observer / Pub-Sub**: `PubSub` for config push, `LeaderElector.Listener`, `ConfigSyncService.onChange`.
- **Repository**: `ConfigRepository` hides the replicated DB (with read-repair and failover).
- **Builder**: `LBConfig.Builder` (immutable, versioned config) and `LoadBalancerSystem.Builder`.
- **Facade / Composition root**: `LoadBalancerSystem`.
- **Reverse Proxy**: `ReverseProxy` (selection, forwarding, retry on connection failure).
- **Dependency Inversion**: stores, leases, metrics, provisioning and DNS are all behind interfaces, so each
  in-memory stand-in could be replaced by Redis, etcd, Prometheus, EC2, etc. without touching the logic.

## Architecture in one picture

```
 users ──DNS──► CoreDNS(DnsServer)            DATA PLANE
   │                                           ──────────
   └────────► LB fleet (LoadBalancerServer) ──► BackendServer x N
                 ▲  ▲  │ heartbeat                 │ heartbeat, /metrics
        PUSH     │  │  ▼                           ▼
   (pub/sub) ────┘  │  Orchestrators x 3  ◄── Prometheus (scraper)    CONTROL PLANE
                    │  (leader election;       ▲                      ─────────────
        PULL (poll) │   only the leader acts)  │ query
                    └── Replicated DB ◄── ConfigService ◄── LB Console / HealthMonitor / AutoScaler(ASG)
```

Key properties demonstrated:
- **Data plane keeps serving** from cached config even if the control plane is down.
- **Push + pull**: pub/sub is fast but lossy; polling guarantees convergence.
- **Heartbeats to a central orchestrator** instead of N x M health probes. Every orchestrator hears every heartbeat,
  so a new leader starts with warm state.
- **Lease-based leader election**: a crashed leader's lease expires and a follower takes over.
- **Versioned configs**: out-of-order or duplicate updates are ignored.

## What the demo shows (`lb.demo.Demo`)

1. Request path through DNS, 2 LBs and 3 backends (even round-robin split).
2. Runtime algorithm switch through the console (LEAST_CONNECTIONS shifts traffic away from a slow backend).
3. Hard backend crash under load: zero user-visible failures (LB retries), the leader marks it DOWN, the ASG replaces it.
4. A running-but-sick app stops heartbeating and is marked DOWN, then recovers.
5. Pub/sub outage: LBs still converge by polling.
6. Orchestrator leader crash: new leader elected, health management continues.
7. LB server death: removed from DNS, clients fail over to the next address.
8. DB primary failure: writes continue through the replica, then read-repair on recovery.
9. CPU-based auto scaling out under load and back in when idle.

## Console API (printed port at startup)

```
GET    /config
POST   /backends?id=<id>&host=<host>&port=<port>
DELETE /backends/<id>
PUT    /algorithm?type=ROUND_ROBIN|LEAST_CONNECTIONS|RANDOM|IP_HASH
PUT    /timeout?ms=<n>
```

## Prototype simplifications

- Pub/sub, DB, lease store and provisioner are in-process stand-ins behind interfaces.
- Intervals are scaled down (heartbeat 300 ms, poll 2 s instead of 15 s, lease 1.5 s).
- No TLS, no HTTP/2, and routing is L7 only (path/headers pass through unchanged).
- Retry-on-failure is limited to idempotent methods or connection failures.

## Ideas to extend

- Weighted round robin / consistent hashing strategies (add a class + one `StrategyFactory` case).
- Replace `InMemoryPubSub` with a Redis client and `InMemoryLeaseStore` with etcd.
- ASG replacement of crashed instances (reap DOWN backends after a grace period).
- Per-backend circuit breaker and connection draining.
