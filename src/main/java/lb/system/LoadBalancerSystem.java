package lb.system;

import lb.backend.BackendServer;
import lb.config.ConfigRepository;
import lb.config.ConfigService;
import lb.config.ConfigSyncService;
import lb.config.InMemoryConfigStore;
import lb.config.ReplicatedConfigRepository;
import lb.console.LBConsoleServer;
import lb.dns.DnsClient;
import lb.dns.DnsRegistry;
import lb.dns.DnsServer;
import lb.model.AlgorithmType;
import lb.model.LBConfig;
import lb.model.ServerInfo;
import lb.monitoring.MetricsScraper;
import lb.monitoring.MetricsStore;
import lb.orchestrator.AutoScaler;
import lb.orchestrator.AutoScalingGroup;
import lb.orchestrator.CpuThresholdScalingPolicy;
import lb.orchestrator.HealthMonitor;
import lb.orchestrator.HeartbeatRegistry;
import lb.orchestrator.InMemoryLeaseStore;
import lb.orchestrator.LeaderElector;
import lb.orchestrator.LeaseStore;
import lb.orchestrator.LocalBackendProvisioner;
import lb.orchestrator.Orchestrator;
import lb.proxy.LoadBalancerServer;
import lb.pubsub.InMemoryPubSub;
import lb.util.Log;
import lb.util.Threads;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facade + composition root: wires every component of the design together and brings the whole cluster up
 * (DNS, orchestrators, backends, LB fleet, console, Prometheus-style scraper).
 */
public class LoadBalancerSystem implements AutoCloseable {
    public static final String LB_DOMAIN = "lb.payments.local";
    private static final String LEADER_LEASE = "orchestrator-leader";

    private final SystemSettings settings;
    private final int lbCount;
    private final int orchestratorCount;
    private final int initialBackends;
    private final AlgorithmType algorithm;
    private final boolean autoScalingAtStart;

    private final InMemoryPubSub pubSub = new InMemoryPubSub();
    private final InMemoryConfigStore primaryDb = new InMemoryConfigStore("db-primary");
    private final InMemoryConfigStore replicaDb = new InMemoryConfigStore("db-replica");
    private final ConfigRepository repository = new ReplicatedConfigRepository(List.of(primaryDb, replicaDb));
    private final ConfigService configService = new ConfigService(repository, pubSub);
    private final LeaseStore leaseStore = new InMemoryLeaseStore();
    private final DnsRegistry dnsRegistry = new DnsRegistry();
    private final MetricsStore metricsStore = new MetricsStore();
    private final List<String> orchestratorUrls = new CopyOnWriteArrayList<>();
    private final List<Orchestrator> orchestrators = new CopyOnWriteArrayList<>();
    private final List<LoadBalancerServer> loadBalancers = new CopyOnWriteArrayList<>();
    private final LocalBackendProvisioner provisioner;
    private final AutoScalingGroup asg;

    private DnsServer dnsServer;
    private DnsClient dnsClient;
    private LBConsoleServer console;
    private MetricsScraper scraper;

    private LoadBalancerSystem(Builder b) {
        this.settings = b.settings;
        this.lbCount = b.lbCount;
        this.orchestratorCount = b.orchestratorCount;
        this.initialBackends = b.initialBackends;
        this.algorithm = b.algorithm;
        this.autoScalingAtStart = b.autoScaling;
        this.provisioner = new LocalBackendProvisioner(() -> orchestratorUrls, settings);
        this.asg = new AutoScalingGroup(provisioner, configService, settings.drainMs());
    }

    public static Builder builder() {
        return new Builder();
    }

    public LoadBalancerSystem start() throws IOException {
        repository.save(LBConfig.initial(algorithm, settings.requestTimeoutMs()));

        dnsServer = new DnsServer("coredns", dnsRegistry);
        ServerInfo dns = dnsServer.start();
        dnsClient = new DnsClient(dns.baseUrl(), settings.dnsCacheTtlMs());

        for (int i = 1; i <= orchestratorCount; i++) {
            Orchestrator orchestrator = createOrchestrator("orch-" + i);
            orchestrators.add(orchestrator);
            orchestratorUrls.add(orchestrator.start().baseUrl());
        }
        for (int i = 0; i < initialBackends; i++) {
            asg.scaleOut("initial capacity");
        }
        for (int i = 1; i <= lbCount; i++) {
            ConfigSyncService sync = new ConfigSyncService("lb-" + i, repository, pubSub, settings.configPollIntervalMs());
            LoadBalancerServer lb = new LoadBalancerServer("lb-" + i, sync, () -> orchestratorUrls, settings);
            lb.start();
            loadBalancers.add(lb);
        }
        console = new LBConsoleServer("console", configService);
        console.start();
        scraper = new MetricsScraper(() -> configService.current().backends(), metricsStore, settings.scrapeIntervalMs());
        scraper.start();

        awaitDns();
        setAutoScaling(autoScalingAtStart);
        return this;
    }

    private Orchestrator createOrchestrator(String id) {
        HeartbeatRegistry registry = new HeartbeatRegistry();
        LeaderElector elector = new LeaderElector(id, leaseStore, LEADER_LEASE, settings.leaseTtlMs());
        HealthMonitor monitor = new HealthMonitor(registry, configService, dnsRegistry, LB_DOMAIN,
                settings.heartbeatTimeoutMs());
        AutoScaler scaler = new AutoScaler(
                new CpuThresholdScalingPolicy(settings.minBackends(), settings.maxBackends(),
                        settings.scaleOutCpu(), settings.scaleInCpu()),
                asg, metricsStore, configService, settings.scaleCooldownMs());
        return new Orchestrator(id, registry, elector, monitor, scaler, settings.orchestratorTickMs());
    }

    private void awaitDns() {
        long deadline = System.currentTimeMillis() + 8000;
        while (dnsRegistry.resolve(LB_DOMAIN).size() < lbCount && System.currentTimeMillis() < deadline) {
            Threads.sleep(50);
        }
        long leaderDeadline = System.currentTimeMillis() + 5000;
        while (currentLeader() == null && System.currentTimeMillis() < leaderDeadline) {
            Threads.sleep(50);
        }
    }

    // ---- accessors used by the demo / tests ----

    public void setAutoScaling(boolean enabled) {
        orchestrators.forEach(o -> o.autoScaler().setEnabled(enabled));
        Log.info("system", "auto scaling " + (enabled ? "ENABLED" : "DISABLED"));
    }

    public Orchestrator currentLeader() {
        return orchestrators.stream().filter(o -> o.isRunning() && o.isLeader()).findFirst().orElse(null);
    }

    public BackendServer backend(String id) {
        return provisioner.get(id);
    }

    public List<BackendServer> backends() {
        return provisioner.all();
    }

    public List<LoadBalancerServer> loadBalancers() {
        return loadBalancers;
    }

    public List<Orchestrator> orchestrators() {
        return orchestrators;
    }

    public InMemoryPubSub pubSub() {
        return pubSub;
    }

    public InMemoryConfigStore primaryDb() {
        return primaryDb;
    }

    public InMemoryConfigStore replicaDb() {
        return replicaDb;
    }

    public ConfigService configService() {
        return configService;
    }

    public AutoScalingGroup asg() {
        return asg;
    }

    public DnsRegistry dnsRegistry() {
        return dnsRegistry;
    }

    public DnsClient dnsClient() {
        return dnsClient;
    }

    public MetricsStore metrics() {
        return metricsStore;
    }

    public ServerInfo consoleInfo() {
        return console.info();
    }

    @Override
    public void close() {
        if (scraper != null) {
            scraper.stop();
        }
        if (console != null) {
            console.stop();
        }
        loadBalancers.forEach(LoadBalancerServer::stop);
        orchestrators.forEach(Orchestrator::stop);
        provisioner.terminateAll();
        asg.shutdown();
        if (dnsServer != null) {
            dnsServer.stop();
        }
        pubSub.close();
    }

    public static final class Builder {
        private SystemSettings settings = SystemSettings.defaults();
        private int lbCount = 2;
        private int orchestratorCount = 3;
        private int initialBackends = settings.minBackends();
        private AlgorithmType algorithm = AlgorithmType.ROUND_ROBIN;
        private boolean autoScaling = true;

        public Builder settings(SystemSettings settings) {
            this.settings = settings;
            return this;
        }

        public Builder loadBalancers(int count) {
            this.lbCount = count;
            return this;
        }

        public Builder orchestrators(int count) {
            this.orchestratorCount = count;
            return this;
        }

        public Builder backends(int count) {
            this.initialBackends = count;
            return this;
        }

        public Builder algorithm(AlgorithmType algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder autoScaling(boolean enabled) {
            this.autoScaling = enabled;
            return this;
        }

        public LoadBalancerSystem build() {
            return new LoadBalancerSystem(this);
        }
    }
}
