package android.content;

import android.content.pm.PackageManager;
import android.content.res.Resources;
import cloudstream.desktop.androidshim.ShimRuntime;
import cloudstream.desktop.androidshim.StoreBackedPreferences;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Desktop Context. Concrete so plugins and the app can use it directly. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    public static final int MODE_WORLD_READABLE = 1;
    public static final int MODE_WORLD_WRITEABLE = 2;
    public static final int MODE_APPEND = 32768;
    public static final String CLIPBOARD_SERVICE = "clipboard";
    public static final String INPUT_METHOD_SERVICE = "input_method";
    public static final String ACTIVITY_SERVICE = "activity";

    private static final Map<String, StoreBackedPreferences> PREFS = new ConcurrentHashMap<>();
    private Resources resources;
    private PackageManager packageManager;

    public Context() { }

    public Context getApplicationContext() { return this; }

    public String getPackageName() { return "com.lagradost.cloudstream3"; }

    public Resources getResources() {
        if (resources == null) resources = new Resources();
        return resources;
    }

    public PackageManager getPackageManager() {
        if (packageManager == null) packageManager = new PackageManager();
        return packageManager;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return PREFS.computeIfAbsent(name, StoreBackedPreferences::new);
    }

    public Object getSystemService(String name) {
        if (CLIPBOARD_SERVICE.equals(name)) return new ClipboardManager();
        if (ACTIVITY_SERVICE.equals(name)) return new android.app.ActivityManager();
        if ("connectivity".equals(name)) return new android.net.ConnectivityManager();
        cloudstream.desktop.androidshim.Stub.hit("android.content.Context.getSystemService(" + name + ")");
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> T getSystemService(Class<T> cls) {
        if (cls == ClipboardManager.class) return (T) new ClipboardManager();
        if (cls == android.app.ActivityManager.class) return (T) new android.app.ActivityManager();
        if (cls == android.net.ConnectivityManager.class) return (T) new android.net.ConnectivityManager();
        return null;
    }

    public void startActivity(Intent intent) {
        if (intent != null && intent.getData() != null && "android.intent.action.VIEW".equals(intent.getAction())) {
            ShimRuntime.openUrl(intent.getData().toString());
        } else {
            cloudstream.desktop.androidshim.Stub.hit("android.content.Context.startActivity");
        }
    }

    public String getString(int resId) { return ""; }
    public String getString(int resId, Object... args) { return ""; }

    public File getFilesDir() { return sub("files"); }
    public File getCacheDir() { return sub("cache"); }

    private static File sub(String n) {
        File f = new File(ShimRuntime.dataDir(), n);
        f.mkdirs();
        return f;
    }

    public Context getBaseContext() { return this; }
    public Resources.Theme getTheme() { return new Resources.Theme(); }
}
