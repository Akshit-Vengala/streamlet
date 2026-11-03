package androidx.core.net;

import android.net.Uri;

public final class UriKt {
    private UriKt() { }

    public static Uri toUri(String s) { return Uri.parse(s); }
    public static Uri toUri(java.io.File f) { return Uri.fromFile(f); }
    public static java.io.File toFile(Uri uri) { return new java.io.File(java.net.URI.create(uri.toString())); }
}
