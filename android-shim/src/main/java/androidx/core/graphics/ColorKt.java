package androidx.core.graphics;

public final class ColorKt {
    private ColorKt() { }

    public static int toColorInt(String s) { return (int) Long.parseLong(s.startsWith("#") ? s.substring(1) : s, 16) | (s.length() <= 7 ? 0xFF000000 : 0); }
    public static int getAlpha(int c) { return c >>> 24; }
    public static int getRed(int c) { return (c >> 16) & 0xFF; }
    public static int getGreen(int c) { return (c >> 8) & 0xFF; }
    public static int getBlue(int c) { return c & 0xFF; }
}
