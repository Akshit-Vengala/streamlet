package android.os;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** The "main looper" is a single daemon thread; Handlers post to it. */
public class Looper {
    private static final Looper MAIN = new Looper("android-main-looper");
    final ScheduledExecutorService executor;
    private final Thread[] thread = new Thread[1];

    private Looper(String name) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            thread[0] = t;
            return t;
        });
    }

    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MAIN; }
    public static void prepare() { }
    public static void loop() { }
    public Thread getThread() { return thread[0]; }
}
