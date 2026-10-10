package lb.node;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lb.model.ServerInfo;
import lb.util.HttpUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Template Method base class for every server in the system (backend, LB, DNS, orchestrator, console).
 * Each node is a real HTTP server on its own ephemeral localhost port; subclasses only declare their routes
 * and lifecycle hooks.
 */
public abstract class HttpNode implements AutoCloseable {
    protected final String id;
    private HttpServer server;
    private ExecutorService executor;
    private volatile ServerInfo info;
    private volatile boolean running;

    protected HttpNode(String id) {
        this.id = id;
    }

    public final synchronized ServerInfo start() throws IOException {
        if (running) {
            return info;
        }
        onStarting();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1024);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        registerHandlers(server);
        server.start();
        info = new ServerInfo(id, "127.0.0.1", server.getAddress().getPort());
        running = true;
        onStarted();
        return info;
    }

    public final synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        onStopping();
        server.stop(0);
        executor.shutdownNow();
    }

    /** Declare the HTTP routes of this node. */
    protected abstract void registerHandlers(HttpServer server);

    /** Hook: runs before the socket is opened. */
    protected void onStarting() throws IOException {}

    /** Hook: runs once the node accepts traffic (start background agents here). */
    protected void onStarted() {}

    /** Hook: runs when the node is shutting down or "crashing". */
    protected void onStopping() {}

    protected final void route(HttpServer httpServer, String path, HttpHandler handler) {
        httpServer.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable t) {
                try {
                    HttpUtil.respond(exchange, 500, "internal error in " + id + ": " + t + "\n");
                } catch (Exception ignored) {
                    // response already started or client gone
                }
            }
        });
    }

    public String id() {
        return id;
    }

    public ServerInfo info() {
        return info;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
    }
}
