package cloudstream.desktop.androidshim;

import android.content.Context;
import cloudstream.desktop.contracts.KeyValueStore;
import com.lagradost.cloudstream3.plugins.BasePlugin;
import com.lagradost.cloudstream3.plugins.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Process-wide hooks the desktop app configures once at startup. Everything has a working
 * in-memory default so plugins load even when the app has not installed real backends.
 */
public final class ShimRuntime {
    private ShimRuntime() { }

    private static volatile KeyValueStore store = new InMemoryStore();
    private static volatile Context context;
    private static volatile boolean verbose = false;
    private static volatile Consumer<String> toastHandler = msg -> { };
    private static volatile Consumer<String> openUrlHandler = url -> { };
    private static volatile CookieBridge cookieBridge;
    private static volatile File dataDir = new File(System.getProperty("java.io.tmpdir"), "cloudstream-desktop");

    /** Install the persistent store (Track H). SharedPreferences are namespaced under "shared_prefs/". */
    public static void setKeyValueStore(KeyValueStore s) { store = s; }
    public static KeyValueStore keyValueStore() { return store; }

    public static void setContext(Context c) { context = c; }

    public static synchronized Context context() {
        if (context == null) {
            context = new Context();
        }
        return context;
    }

    public static void setDataDir(File dir) { dataDir = dir; }
    public static File dataDir() { return dataDir; }

    /** App notification sink for android.widget.Toast. */
    public static void setToastHandler(Consumer<String> h) { toastHandler = h; }
    public static void toast(String msg) { toastHandler.accept(msg); }

    /** Sink for Intent ACTION_VIEW / openBrowser style requests. */
    public static void setOpenUrlHandler(Consumer<String> h) { openUrlHandler = h; }
    public static void openUrl(String url) { openUrlHandler.accept(url); }

    public static void setStubLoggingVerbose(boolean v) { verbose = v; }
    public static boolean isStubLoggingVerbose() { return verbose; }

    /** Track E wires this to the JCEF cookie store (android.webkit.CookieManager delegates to it). */
    public interface CookieBridge {
        String getCookie(String url);
        void setCookie(String url, String cookie);
        void removeAll();
    }
    public static void setCookieBridge(CookieBridge b) { cookieBridge = b; }
    public static CookieBridge cookieBridge() { return cookieBridge; }

    /**
     * Whether a loaded plugin ships Android settings UI that cannot run on desktop. The app shows
     * "settings unavailable on desktop" when this is true (unless it provides a native settings screen).
     */
    public static boolean hasUnavailableSettingsUi(BasePlugin plugin) {
        if (plugin instanceof Plugin) {
            Plugin p = (Plugin) plugin;
            return p.getOpenSettings() != null;
        }
        return false;
    }

    /** Distinct unimplemented android members that plugins invoked so far. */
    public static java.util.Set<String> unimplementedHits() { return Stub.hits(); }

    /** Simple map-backed store used until the app installs a real one. */
    static final class InMemoryStore implements KeyValueStore {
        private final Map<String, Object> map = new ConcurrentHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            Object v = map.get(key);
            if (v == null) return null;
            if (type.isInstance(v)) return (T) v;
            if (type == String.class) return (T) String.valueOf(v);
            return null;
        }

        @Override
        public void set(String key, Object value) {
            if (value == null) map.remove(key); else map.put(key, value);
        }

        @Override
        public void remove(String key) { map.remove(key); }

        @Override
        public List<String> keys(String prefix) {
            List<String> r = new ArrayList<>();
            for (String k : map.keySet()) if (k.startsWith(prefix)) r.add(k);
            return r;
        }
    }
}
