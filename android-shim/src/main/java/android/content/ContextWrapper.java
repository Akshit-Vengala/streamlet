package android.content;

public class ContextWrapper extends Context {
    private Context base;

    public ContextWrapper() { }
    public ContextWrapper(Context base) { this.base = base; }

    protected void attachBaseContext(Context base) { this.base = base; }

    @Override
    public Context getBaseContext() { return base != null ? base : cloudstream.desktop.androidshim.ShimRuntime.context(); }

    @Override
    public Context getApplicationContext() { return getBaseContext(); }

    @Override
    public android.content.res.Resources getResources() { return getBaseContext().getResources(); }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) { return getBaseContext().getSharedPreferences(name, mode); }
}
