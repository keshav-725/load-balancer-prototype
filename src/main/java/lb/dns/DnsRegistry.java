package lb.dns;

import lb.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** The record table behind our self-hosted CoreDNS: domain name -> LB addresses. */
public class DnsRegistry {
    private final Map<String, CopyOnWriteArraySet<String>> records = new ConcurrentHashMap<>();

    public void register(String name, String address) {
        if (records.computeIfAbsent(name, n -> new CopyOnWriteArraySet<>()).add(address)) {
            Log.info("dns", "+ " + name + " -> " + address);
        }
    }

    public void deregister(String name, String address) {
        CopyOnWriteArraySet<String> addresses = records.get(name);
        if (addresses != null && addresses.remove(address)) {
            Log.info("dns", "- " + name + " -> " + address);
        }
    }

    public List<String> resolve(String name) {
        CopyOnWriteArraySet<String> addresses = records.get(name);
        return addresses == null ? List.of() : List.copyOf(addresses);
    }
}
