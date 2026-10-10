package lb.model;

/** Network identity of a node (backend, LB, ...). Immutable value object. */
public record ServerInfo(String id, String host, int port) {
    public String address() {
        return host + ":" + port;
    }

    public String baseUrl() {
        return "http://" + address();
    }
}
