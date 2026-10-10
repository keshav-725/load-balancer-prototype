package lb.pubsub;

import lb.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * In-process stand-in for Redis pub/sub: asynchronous, fire-and-forget, no replay.
 * Delivery can be switched off to simulate an outage / lost messages.
 */
public class InMemoryPubSub implements PubSub, AutoCloseable {

    private record Subscriber(Consumer<Object> handler) {}

    private final Map<String, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService delivery = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean deliveryEnabled = true;

    @Override
    public <T> Subscription subscribe(String topic, Class<T> type, Consumer<T> handler) {
        Subscriber subscriber = new Subscriber(message -> handler.accept(type.cast(message)));
        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> {
            List<Subscriber> list = subscribers.get(topic);
            if (list != null) {
                list.remove(subscriber);
            }
        };
    }

    @Override
    public void publish(String topic, Object message) {
        if (!deliveryEnabled) {
            Log.info("pubsub", "message on '" + topic + "' LOST (pub/sub outage)");
            return;
        }
        List<Subscriber> list = subscribers.get(topic);
        if (list == null) {
            return;
        }
        for (Subscriber subscriber : list) {
            delivery.execute(() -> {
                try {
                    subscriber.handler().accept(message);
                } catch (RuntimeException e) {
                    Log.info("pubsub", "subscriber failed: " + e);
                }
            });
        }
    }

    public void setDeliveryEnabled(boolean enabled) {
        this.deliveryEnabled = enabled;
        Log.info("pubsub", enabled ? "delivery RESTORED" : "delivery DISABLED (simulated outage)");
    }

    @Override
    public void close() {
        delivery.shutdownNow();
    }
}
