package androidx.core.graphics.drawable;

import android.graphics.drawable.ColorDrawable;

public final class ColorDrawableKt {
    private ColorDrawableKt() { }

    public static ColorDrawable toDrawable(int color) { return new ColorDrawable(color); }
}
