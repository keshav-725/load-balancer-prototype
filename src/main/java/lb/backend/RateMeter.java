package lb.backend;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Sliding-window request-rate meter used to derive simulated CPU load. */
public class RateMeter {
    private final long windowMs;
    private final ConcurrentLinkedQueue<Long> events = new ConcurrentLinkedQueue<>();

    public RateMeter(long windowMs) {
        this.windowMs = windowMs;
    }

    public void mark() {
        events.add(System.currentTimeMillis());
    }

    public double perSecond() {
        long cutoff = System.currentTimeMillis() - windowMs;
        Long head;
        while ((head = events.peek()) != null && head < cutoff) {
            events.poll();
        }
        return events.size() * 1000.0 / windowMs;
    }
}
