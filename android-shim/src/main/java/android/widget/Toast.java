package android.widget;

import android.content.Context;
import cloudstream.desktop.androidshim.ShimRuntime;

/** Toasts are forwarded to the app's notification sink ({@link ShimRuntime#setToastHandler}). */
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    private CharSequence text;
    private int duration;

    public Toast(Context context) { }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        Toast t = new Toast(context);
        t.text = text;
        t.duration = duration;
        return t;
    }

    public static Toast makeText(Context context, int resId, int duration) {
        return makeText(context, context == null ? "" : context.getString(resId), duration);
    }

    public void show() { if (text != null && text.length() > 0) ShimRuntime.toast(text.toString()); }
    public void cancel() { }
    public void setText(CharSequence t) { text = t; }
    public void setDuration(int d) { duration = d; }
    public int getDuration() { return duration; }
}
