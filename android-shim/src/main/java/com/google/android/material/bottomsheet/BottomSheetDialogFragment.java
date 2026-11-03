// Hand-written (generator skips it): a DialogFragment whose dialog is a BottomSheetDialog, as on Android.
package com.google.android.material.bottomsheet;

public class BottomSheetDialogFragment extends androidx.fragment.app.DialogFragment {
    public BottomSheetDialogFragment() { }

    @Override
    public android.app.Dialog onCreateDialog(android.os.Bundle savedInstanceState) {
        return new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
    }
}
