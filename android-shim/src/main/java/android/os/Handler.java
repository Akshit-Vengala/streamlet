package android.os;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Handler {
    private final Looper looper;
    private final Map<Runnable, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public interface Callback {
        boolean handleMessage(Message msg);
    }

    public Handler() { this(Looper.getMainLooper()); }
    public Handler(Looper looper) { this.looper = looper == null ? Looper.getMainLooper() : looper; }
    public Handler(Looper looper, Callback callback) { this(looper); }

    public final boolean post(Runnable r) { return postDelayed(r, 0); }

    public final boolean postDelayed(Runnable r, long delayMillis) {
        if (r == null) return false;
        ScheduledFuture<?> f = looper.executor.schedule(() -> {
            pending.remove(r);
            try { r.run(); } catch (Throwable t) { t.printStackTrace(); }
        }, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        pending.put(r, f);
        return true;
    }

    public final void removeCallbacks(Runnable r) {
        ScheduledFuture<?> f = pending.remove(r);
        if (f != null) f.cancel(false);
    }

    public final void removeCallbacksAndMessages(Object token) {
        for (ScheduledFuture<?> f : pending.values()) f.cancel(false);
        pending.clear();
    }

    public Looper getLooper() { return looper; }
    public void handleMessage(Message msg) { }
    public final boolean sendMessage(Message msg) { return true; }
    public final Message obtainMessage() { return new Message(); }
}
