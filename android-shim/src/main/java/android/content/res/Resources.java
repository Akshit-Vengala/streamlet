package android.content.res;

import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

/** Plugin resources are not available on desktop; lookups return harmless values. */
public class Resources {
    public Resources() { }

    public DisplayMetrics getDisplayMetrics() {
        DisplayMetrics m = new DisplayMetrics();
        m.density = 1f;
        m.widthPixels = 1280;
        m.heightPixels = 720;
        return m;
    }

    public Drawable getDrawable(int id, Theme theme) {
        cloudstream.desktop.androidshim.Stub.hit("android.content.res.Resources.getDrawable");
        return new android.graphics.drawable.ColorDrawable();
    }

    public int getIdentifier(String name, String defType, String defPackage) {
        cloudstream.desktop.androidshim.Stub.hit("android.content.res.Resources.getIdentifier");
        return 0;
    }

    public XmlResourceParser getLayout(int id) {
        cloudstream.desktop.androidshim.Stub.hit("android.content.res.Resources.getLayout");
        return null;
    }

    public String getString(int id) { return ""; }

    public static class Theme {
        public Theme() { }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException() { super(); }
        public NotFoundException(String message) { super(message); }
    }
}
