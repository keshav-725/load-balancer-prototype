package lb.util;

/** Tiny timestamped console logger shared by every component. */
public final class Log {
    private static final long START = System.currentTimeMillis();

    private Log() {}

    public static synchronized void info(String tag, String message) {
        System.out.printf("[%6.2fs] %-10s %s%n", (System.currentTimeMillis() - START) / 1000.0, tag, message);
    }
}
