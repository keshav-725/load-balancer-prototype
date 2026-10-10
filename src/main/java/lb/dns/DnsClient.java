package lb.dns;

import lb.util.HttpUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A client-side stub resolver with a TTL cache (so stale answers after an LB dies are realistic). */
public class DnsClient {
    private record Entry(List<String> addresses, long expiresAtMs) {}

    private final String resolverBaseUrl;
    private final long ttlMs;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public DnsClient(String resolverBaseUrl, long ttlMs) {
        this.resolverBaseUrl = resolverBaseUrl;
        this.ttlMs = ttlMs;
    }

    public List<String> resolve(String name) {
        long now = System.currentTimeMillis();
        Entry cached = cache.get(name);
        if (cached != null && now < cached.expiresAtMs()) {
            return cached.addresses();
        }
        try {
            HttpUtil.Result result = HttpUtil.get(resolverBaseUrl + "/resolve?name=" + name, 500);
            List<String> addresses = result.ok() ? Arrays.asList(result.body().trim().split("\n")) : List.of();
            cache.put(name, new Entry(addresses, now + ttlMs));
            return addresses;
        } catch (IOException e) {
            return cached == null ? List.of() : cached.addresses();
        }
    }
}
