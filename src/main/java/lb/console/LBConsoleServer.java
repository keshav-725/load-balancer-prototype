package lb.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lb.config.ConfigService;
import lb.model.AlgorithmType;
import lb.model.LBConfig;
import lb.model.ServerInfo;
import lb.node.HttpNode;
import lb.util.HttpUtil;

import java.io.IOException;
import java.util.Map;

/**
 * The LB Console API a developer uses:
 * GET /config, POST /backends?id&host&port, DELETE /backends/{id}, PUT /algorithm?type=, PUT /timeout?ms=
 */
public class LBConsoleServer extends HttpNode {
    private final ConfigService configService;

    public LBConsoleServer(String id, ConfigService configService) {
        super(id);
        this.configService = configService;
    }

    @Override
    protected void registerHandlers(HttpServer server) {
        route(server, "/config", exchange -> HttpUtil.respond(exchange, 200, configService.current() + "\n"));
        route(server, "/backends", this::handleBackends);
        route(server, "/algorithm", this::handleAlgorithm);
        route(server, "/timeout", this::handleTimeout);
    }

    private void handleBackends(HttpExchange exchange) throws IOException {
        Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getRawQuery());
        String path = exchange.getRequestURI().getPath();
        try {
            switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    ServerInfo server = new ServerInfo(q.get("id"), q.getOrDefault("host", "127.0.0.1"),
                            Integer.parseInt(q.get("port")));
                    reply(exchange, configService.addBackend(server));
                }
                case "DELETE" -> reply(exchange, configService.removeBackend(path.substring("/backends/".length())));
                case "GET" -> HttpUtil.respond(exchange, 200, configService.current().backends() + "\n");
                default -> HttpUtil.respond(exchange, 405, "method not allowed\n");
            }
        } catch (RuntimeException e) {
            HttpUtil.respond(exchange, 400, "bad request: " + e.getMessage() + "\n");
        }
    }

    private void handleAlgorithm(HttpExchange exchange) throws IOException {
        Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            AlgorithmType type = AlgorithmType.valueOf(q.getOrDefault("type", "").toUpperCase());
            reply(exchange, configService.changeAlgorithm(type));
        } catch (IllegalArgumentException e) {
            HttpUtil.respond(exchange, 400, "unknown algorithm; use one of " + java.util.Arrays.toString(AlgorithmType.values()) + "\n");
        }
    }

    private void handleTimeout(HttpExchange exchange) throws IOException {
        Map<String, String> q = HttpUtil.parseQuery(exchange.getRequestURI().getRawQuery());
        try {
            reply(exchange, configService.changeTimeout(Integer.parseInt(q.getOrDefault("ms", ""))));
        } catch (NumberFormatException e) {
            HttpUtil.respond(exchange, 400, "ms must be an integer\n");
        }
    }

    private void reply(HttpExchange exchange, LBConfig config) throws IOException {
        HttpUtil.respond(exchange, 200, "OK " + config + "\n");
    }
}
