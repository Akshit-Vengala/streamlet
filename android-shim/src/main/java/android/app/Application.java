package android.app;

public class Application extends android.content.ContextWrapper {
    public Application() { }

    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c) { }
    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks c) { }

    public interface ActivityLifecycleCallbacks {
        default void onActivityCreated(Activity a, android.os.Bundle b) { }
        default void onActivityStarted(Activity a) { }
        default void onActivityResumed(Activity a) { }
        default void onActivityPaused(Activity a) { }
        default void onActivityStopped(Activity a) { }
        default void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
        default void onActivityDestroyed(Activity a) { }
    }
}
