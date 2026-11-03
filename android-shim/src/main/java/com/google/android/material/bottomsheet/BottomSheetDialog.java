// Hand-written (generator skips it): a real Dialog (as on Android: BottomSheetDialog -> AppCompatDialog -> Dialog),
// so plugins can cast the dialog from onCreateDialog() and its content's WebViews are shown/hidden with it.
package com.google.android.material.bottomsheet;

public class BottomSheetDialog extends android.app.Dialog {
    private final com.google.android.material.bottomsheet.BottomSheetBehavior behavior = new com.google.android.material.bottomsheet.BottomSheetBehavior();

    public BottomSheetDialog() { }
    public BottomSheetDialog(android.content.Context context) { super(context); }
    public BottomSheetDialog(android.content.Context context, int theme) { super(context, theme); }

    public com.google.android.material.bottomsheet.BottomSheetBehavior getBehavior() { return behavior; }
}
