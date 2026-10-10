package lb.pubsub;

import java.util.function.Consumer;

/** Minimal publish/subscribe abstraction (the "Redis as PubSub" box in the design). */
public interface PubSub {

    interface Subscription {
        void cancel();
    }

    <T> Subscription subscribe(String topic, Class<T> type, Consumer<T> handler);

    void publish(String topic, Object message);
}
