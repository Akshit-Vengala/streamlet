// Hand-written (generator skips it): lifecycle accessors return the headless activity, if any.
package androidx.fragment.app;

public class Fragment {
    /** True between a DialogFragment's show() and its dismissal (Android: attached to its FragmentManager). */
    boolean added;

    public FragmentActivity getActivity() {
        android.app.Activity a = com.lagradost.cloudstream3.CommonActivity.INSTANCE.getActivity();
        return a instanceof FragmentActivity ? (FragmentActivity) a : null;
    }
    public FragmentActivity requireActivity() { return getActivity(); }
    public android.content.Context getContext() { return cloudstream.desktop.androidshim.ShimRuntime.context(); }
    public android.content.Context requireContext() { return getContext(); }
    // Android: requireContext().getResources() / getString(...). Plugin Turnstile dialogs size their views with them.
    public final android.content.res.Resources getResources() { return requireContext().getResources(); }
    public final java.lang.String getString(int resId) { return getResources().getString(resId); }
    public final java.lang.String getString(int resId, java.lang.Object... formatArgs) {
        java.lang.String f = getString(resId);
        return f.isEmpty() ? f : java.lang.String.format(f, formatArgs);
    }
    public boolean isAdded() { return added; }
    public android.os.Bundle getArguments() { return null; }
    public void setArguments(android.os.Bundle b) { }

    // Lifecycle callbacks (no-ops by default, as on Android).
    public void onCreate(android.os.Bundle savedInstanceState) { }
    public android.view.View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container, android.os.Bundle savedInstanceState) { return null; }
    public void onViewCreated(android.view.View view, android.os.Bundle savedInstanceState) { }
    public void onStart() { }
    public void onResume() { }
    public void onPause() { }
    public void onStop() { }
    public void onDestroyView() { }
    public void onDestroy() { }
    public void onConfigurationChanged(android.content.res.Configuration newConfig) { }

    public Fragment() { }
}
