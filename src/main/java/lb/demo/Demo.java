package lb.demo;

import lb.backend.BackendServer;
import lb.model.AlgorithmType;
import lb.model.LBConfig;
import lb.model.ServerInfo;
import lb.orchestrator.Orchestrator;
import lb.proxy.LoadBalancerServer;
import lb.system.LoadBalancerSystem;
import lb.system.SystemSettings;
import lb.util.HttpUtil;
import lb.util.Log;
import lb.util.Threads;

import java.io.IOException;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;

/** Scripted end-to-end tour of the system. Run with: ./run.sh */
public final class Demo {
    private Demo() {}

    public static void main(String[] args) throws Exception {
        try (LoadBalancerSystem sys = LoadBalancerSystem.builder()
                .settings(SystemSettings.defaults())
                .loadBalancers(2)
                .orchestrators(3)
                .backends(3)
                .algorithm(AlgorithmType.ROUND_ROBIN)
                .autoScaling(false)
                .build()) {

            sys.start();
            LoadGenerator users = new LoadGenerator(sys.dnsClient(), LoadBalancerSystem.LB_DOMAIN);
            String console = sys.consoleInfo().baseUrl();

            section("0. Cluster is up");
            printTopology(sys);

            section("1. Request path: DNS -> LB fleet -> backends (ROUND_ROBIN), 120 rps for 3s");
            System.out.println(users.run(120, 3000).summary("baseline traffic"));

            section("2. Console changes the algorithm at runtime (backend-3 is made slow: +150ms)");
            sys.backend("backend-3").setExtraLatencyMs(150);
            System.out.println(users.run(120, 3000).summary("ROUND_ROBIN: slow backend still gets its 1/3 share"));
            console("PUT", console + "/algorithm?type=LEAST_CONNECTIONS");
            waitUntil("all LBs switched to LEAST_CONNECTIONS", () -> sys.loadBalancers().stream()
                    .allMatch(lb -> lb.algorithm() == AlgorithmType.LEAST_CONNECTIONS), 5000);
            System.out.println(users.run(120, 3000).summary("LEAST_CONNECTIONS: traffic shifts away from the slow backend"));
            console("PUT", console + "/algorithm?type=ROUND_ROBIN");
            sys.backend("backend-3").setExtraLatencyMs(0);
            waitUntil("all LBs back on ROUND_ROBIN", () -> sys.loadBalancers().stream()
                    .allMatch(lb -> lb.algorithm() == AlgorithmType.ROUND_ROBIN), 5000);

            section("3. A backend crashes under load; heartbeats stop, the leader marks it DOWN, the ASG replaces it");
            sys.setAutoScaling(true);
            LoadGenerator.Run live = users.start(100);
            Threads.sleep(1000);
            Log.info("demo", ">>> CRASH backend-2 (hard kill)");
            sys.backend("backend-2").stop();
            waitUntil("backend-2 marked unhealthy", () -> sys.configService().current().unhealthyIds().contains("backend-2"), 8000);
            waitUntil("replacement online (3 healthy backends again)",
                    () -> sys.configService().current().healthyBackends().size() >= 3, 10000);
            Threads.sleep(1500);
            LoadReport crashReport = live.stop();
            System.out.println(crashReport.summary("traffic during the crash"));
            long retries = sys.loadBalancers().stream().mapToLong(LoadBalancerServer::retryCount).sum();
            System.out.println("  LB-level retries that hid the dead backend from users: " + retries);
            sys.setAutoScaling(false);
            console("DELETE", console + "/backends/backend-2");

            section("4. A running-but-sick app stops heartbeating, then recovers");
            BackendServer sick = sys.backend("backend-1");
            sick.setHealthy(false);
            waitUntil("backend-1 marked unhealthy", () -> sys.configService().current().unhealthyIds().contains("backend-1"), 8000);
            sick.setHealthy(true);
            waitUntil("backend-1 marked healthy again", () -> !sys.configService().current().unhealthyIds().contains("backend-1"), 8000);

            section("5. Push vs pull: pub/sub is down, a config change still reaches every LB by polling");
            sys.pubSub().setDeliveryEnabled(false);
            sys.asg().scaleOut("manual capacity add");
            long dbVersion = sys.configService().current().version();
            sys.loadBalancers().forEach(lb -> Log.info("demo", lb.id() + " is on v" + lb.configVersion() + " (DB is on v" + dbVersion + ")"));
            waitUntil("every LB converged to v" + dbVersion + " via POLL", () -> sys.loadBalancers().stream()
                    .allMatch(lb -> lb.configVersion() >= dbVersion), 10000);
            sys.pubSub().setDeliveryEnabled(true);

            section("6. Orchestrator leader crashes; a follower takes over and keeps managing health");
            Orchestrator oldLeader = sys.currentLeader();
            Log.info("demo", "current leader: " + oldLeader.id());
            LoadGenerator.Run during = users.start(100);
            oldLeader.crash();
            waitUntil("a new leader is elected", () -> {
                Orchestrator leader = sys.currentLeader();
                return leader != null && leader != oldLeader;
            }, 10000);
            Log.info("demo", "new leader: " + sys.currentLeader().id() + " (data plane never noticed)");
            BackendServer sick2 = sys.backend("backend-3");
            sick2.setHealthy(false);
            waitUntil("new leader marks backend-3 unhealthy", () -> sys.configService().current().unhealthyIds().contains("backend-3"), 8000);
            sick2.setHealthy(true);
            waitUntil("new leader marks backend-3 healthy again", () -> !sys.configService().current().unhealthyIds().contains("backend-3"), 8000);
            System.out.println(during.stop().summary("traffic across the leader failover"));

            section("7. An LB server dies; the leader removes it from DNS and clients fail over");
            LoadGenerator.Run lbRun = users.start(100);
            Threads.sleep(1000);
            LoadBalancerServer victim = sys.loadBalancers().get(0);
            Log.info("demo", ">>> KILL " + victim.id());
            victim.stop();
            waitUntil("DNS no longer lists the dead LB", () -> sys.dnsRegistry().resolve(LoadBalancerSystem.LB_DOMAIN).size() == 1, 8000);
            Threads.sleep(1500);
            System.out.println(lbRun.stop().summary("traffic while an LB died (clients retried the next DNS address)"));

            section("8. Database primary fails; config changes keep working through the replica");
            sys.primaryDb().setAvailable(false);
            console("PUT", console + "/algorithm?type=LEAST_CONNECTIONS");
            Log.info("demo", "versions: primary=v" + sys.primaryDb().peekVersion() + " (down), replica=v" + sys.replicaDb().peekVersion());
            System.out.println(users.run(60, 1500).summary("traffic with the primary DB down"));
            sys.primaryDb().setAvailable(true);
            sys.configService().current(); // a read repairs the stale primary
            Log.info("demo", "after recovery: primary=v" + sys.primaryDb().peekVersion() + ", replica=v" + sys.replicaDb().peekVersion());
            console("PUT", console + "/algorithm?type=ROUND_ROBIN");

            section("9. Auto scaling: heavy load scales out, idle time scales back in");
            sys.setAutoScaling(true);
            LoadGenerator.Run heavy = users.start(250);
            for (int sec = 1; sec <= 12; sec++) {
                Threads.sleep(1000);
                printScalingState(sys, "load 250 rps, t=" + sec + "s");
            }
            LoadReport heavyReport = heavy.stop();
            System.out.println(heavyReport.summary("traffic during scale-out"));
            Log.info("demo", ">>> load stops");
            for (int sec = 1; sec <= 12; sec++) {
                Threads.sleep(1000);
                printScalingState(sys, "idle, t=" + sec + "s");
            }

            section("Final state");
            printTopology(sys);
        }
        System.exit(0);
    }

    private static void printTopology(LoadBalancerSystem sys) {
        LBConfig config = sys.configService().current();
        System.out.println("  config        : " + config);
        System.out.println("  DNS           : " + LoadBalancerSystem.LB_DOMAIN + " -> " + sys.dnsRegistry().resolve(LoadBalancerSystem.LB_DOMAIN));
        System.out.println("  console       : " + sys.consoleInfo().baseUrl());
        for (Orchestrator o : sys.orchestrators()) {
            System.out.println("  orchestrator  : " + o.id() + (o.isRunning() ? (o.isLeader() ? "  <- LEADER" : "  (follower)") : "  (down)"));
        }
        for (LoadBalancerServer lb : sys.loadBalancers()) {
            System.out.println("  LB server     : " + lb.id() + (lb.isRunning() ? " " + lb.info().address() + " config=v" + lb.configVersion() + " algo=" + lb.algorithm() : " (down)"));
        }
        for (ServerInfo b : config.backends()) {
            System.out.println("  backend       : " + b.id() + " " + b.address() + (config.unhealthyIds().contains(b.id()) ? "  DOWN" : "  up"));
        }
    }

    private static void printScalingState(LoadBalancerSystem sys, String label) {
        LBConfig config = sys.configService().current();
        OptionalDouble cpu = sys.metrics().averageCpu(2000);
        System.out.printf("  %-26s healthy backends=%d  avg cpu=%s%n", label, config.healthyBackends().size(),
                cpu.isPresent() ? String.format("%.0f%%", cpu.getAsDouble()) : "n/a");
    }

    private static void console(String method, String url) {
        try {
            HttpUtil.Result r = HttpUtil.send(method, url, 2000, java.util.Map.of());
            Log.info("console", method + " " + url.substring(url.indexOf("/", 8)) + " -> " + r.status());
        } catch (IOException e) {
            Log.info("console", method + " failed: " + e);
        }
    }

    private static long waitUntil(String what, BooleanSupplier condition, long timeoutMs) {
        long started = System.currentTimeMillis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - started > timeoutMs) {
                Log.info("demo", "TIMEOUT waiting for: " + what);
                return -1;
            }
            Threads.sleep(50);
        }
        long elapsed = System.currentTimeMillis() - started;
        Log.info("demo", what + "  (after " + elapsed + " ms)");
        return elapsed;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════");
        System.out.println(" " + title);
        System.out.println("════════════════════════════════════════════════════════════════════════");
    }
}
