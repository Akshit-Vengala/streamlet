// Hand-written (generator skips it): the DialogFragment lifecycle a plugin relies on, in Android's order.
//   show():    onCreateDialog -> onCreateView -> onViewCreated -> (content set) -> onStart -> dialog shown
//   dismiss(): dialog dismissed -> onDismiss -> onDestroyView -> onDestroy
package androidx.fragment.app;

public class DialogFragment extends androidx.fragment.app.Fragment implements android.content.DialogInterface.OnDismissListener {
    private android.app.Dialog dialog;
    private boolean dismissed;
    private boolean cancelable = true;

    public DialogFragment() { }

    public android.app.Dialog onCreateDialog(android.os.Bundle savedInstanceState) { return new android.app.Dialog(requireContext()); }
    public android.app.Dialog getDialog() { return dialog; }
    public android.app.Dialog requireDialog() { return dialog; }

    public void show(androidx.fragment.app.FragmentManager manager, java.lang.String tag) {
        if (added) return;
        added = true; dismissed = false;
        onCreate(null);
        dialog = onCreateDialog(null);
        android.view.View view = onCreateView(new android.view.LayoutInflater(), null, null);
        if (view != null) {
            onViewCreated(view, null);
            if (dialog != null) dialog.setContentView(view);
        }
        onStart();
        if (dialog != null) dialog.show();
        onResume();
    }

    public void showNow(androidx.fragment.app.FragmentManager manager, java.lang.String tag) { show(manager, tag); }

    public void dismiss() { dismissInternal(); }
    public void dismissAllowingStateLoss() { dismissInternal(); }
    public void dismissNow() { dismissInternal(); }

    private void dismissInternal() {
        if (!added || dismissed) return;
        dismissed = true;
        if (dialog != null) dialog.dismiss();
        onPause();
        onStop();
        onDismiss(dialog);
        onDestroyView();
        onDestroy();
        added = false;
    }

    @Override public void onDismiss(android.content.DialogInterface dialog) { }
    public void onCancel(android.content.DialogInterface dialog) { }
    public void setCancelable(boolean c) { cancelable = c; if (dialog != null) dialog.setCancelable(c); }
    public boolean isCancelable() { return cancelable; }
    public void setStyle(int style, int theme) { }
    public boolean getShowsDialog() { return true; }
    public void setShowsDialog(boolean showsDialog) { }
}
