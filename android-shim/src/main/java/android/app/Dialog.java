// Hand-written (generator skips it). A plugin dialog has no window of its own on desktop; what matters is the
// WebView it may contain: show() puts that WebView's real browser window on screen, dismiss() takes it away -
// the same moments it would appear/disappear on Android.
package android.app;

import cloudstream.desktop.androidshim.ViewTree;

public class Dialog implements android.content.DialogInterface {
    private android.view.View content;
    private boolean showing;
    private boolean cancelable = true;
    private android.content.DialogInterface.OnDismissListener onDismiss;
    private android.content.DialogInterface.OnShowListener onShow;
    private android.content.DialogInterface.OnKeyListener onKey;

    public Dialog() { }
    public Dialog(android.content.Context context) { }
    public Dialog(android.content.Context context, int themeResId) { }

    public android.content.Context getContext() { return cloudstream.desktop.androidshim.ShimRuntime.context(); }

    public void setContentView(android.view.View view) {
        boolean wasShowing = showing;
        if (wasShowing) setWebViewsShown(false);
        content = view;
        if (wasShowing) setWebViewsShown(true);
    }

    public void show() {
        if (showing) return;
        showing = true;
        setWebViewsShown(true);
        if (onShow != null) onShow.onShow(this);
    }

    @Override public void dismiss() {
        if (!showing) return;
        showing = false;
        setWebViewsShown(false);
        if (onDismiss != null) onDismiss.onDismiss(this);
    }

    @Override public void cancel() { if (cancelable) dismiss(); }
    public boolean isShowing() { return showing; }
    public void setCancelable(boolean flag) { cancelable = flag; }
    public void setCanceledOnTouchOutside(boolean cancel) { }
    public void setOnDismissListener(android.content.DialogInterface.OnDismissListener l) { onDismiss = l; }
    public void setOnShowListener(android.content.DialogInterface.OnShowListener l) { onShow = l; }
    public void setOnKeyListener(android.content.DialogInterface.OnKeyListener l) { onKey = l; }
    public boolean dispatchKeyEvent(android.view.KeyEvent event) { return false; }
    public android.view.View findViewById(int id) { return new android.view.View(); }
    public android.view.Window getWindow() { return new android.view.Window(); }
    public boolean requestWindowFeature(int featureId) { return false; }

    private void setWebViewsShown(boolean shown) {
        if (content == null) return;
        for (Object v : ViewTree.descendants(content)) {
            if (v instanceof android.webkit.WebView) ((android.webkit.WebView) v).desktopSetOnScreen(shown);
        }
    }
}
