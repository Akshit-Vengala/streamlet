package android.text;

public final class TextUtils {
    private TextUtils() { }

    public enum TruncateAt { START, MIDDLE, END, MARQUEE }

    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (Object o : tokens) {
            if (!first) b.append(delimiter);
            first = false;
            b.append(o);
        }
        return b.toString();
    }

    public static String join(CharSequence delimiter, Object[] tokens) { return join(delimiter, java.util.Arrays.asList(tokens)); }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        return a != null && b != null && a.toString().contentEquals(b);
    }

    public static String htmlEncode(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static CharSequence ellipsize(CharSequence text, Object paint, float avail, TruncateAt where) { return text; }
}
