package lb.dns;

import com.sun.net.httpserver.HttpServer;
import lb.node.HttpNode;
import lb.util.HttpUtil;

import java.util.List;

/** CoreDNS stand-in: GET /resolve?name=... returns one address per line (404 = NXDOMAIN). */
public class DnsServer extends HttpNode {
    private final DnsRegistry registry;

    public DnsServer(String id, DnsRegistry registry) {
        super(id);
        this.registry = registry;
    }

    @Override
    protected void registerHandlers(HttpServer server) {
        route(server, "/resolve", exchange -> {
            String name = HttpUtil.parseQuery(exchange.getRequestURI().getRawQuery()).get("name");
            List<String> addresses = name == null ? List.of() : registry.resolve(name);
            if (addresses.isEmpty()) {
                HttpUtil.respond(exchange, 404, "NXDOMAIN\n");
            } else {
                HttpUtil.respond(exchange, 200, String.join("\n", addresses));
            }
        });
    }
}
