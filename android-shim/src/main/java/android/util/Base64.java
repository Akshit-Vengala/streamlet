package android.util;

import java.nio.charset.StandardCharsets;

/** android.util.Base64 semantics (DEFAULT wraps at 76 chars with a trailing newline). */
public final class Base64 {
    public static final int DEFAULT = 0, NO_PADDING = 1, NO_WRAP = 2, CRLF = 4, URL_SAFE = 8, NO_CLOSE = 16;

    private Base64() { }

    public static byte[] decode(String str, int flags) { return decode(str.getBytes(StandardCharsets.ISO_8859_1), flags); }

    public static byte[] decode(byte[] input, int flags) { return decode(input, 0, input.length, flags); }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        String s = new String(input, offset, len, StandardCharsets.ISO_8859_1);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if ((flags & URL_SAFE) != 0) { if (c == '+') c = '-'; else if (c == '/') c = '_'; }
            else { if (c == '-') c = '+'; else if (c == '_') c = '/'; }
            b.append(c);
        }
        String t = b.toString();
        while (t.endsWith("=")) t = t.substring(0, t.length() - 1);
        if (t.length() % 4 == 1) throw new IllegalArgumentException("bad base-64");
        try {
            return ((flags & URL_SAFE) != 0 ? java.util.Base64.getUrlDecoder() : java.util.Base64.getDecoder()).decode(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base-64", e);
        }
    }

    public static byte[] encode(byte[] input, int flags) { return encode(input, 0, input.length, flags); }

    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        return encodeToString(input, offset, len, flags).getBytes(StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] input, int flags) { return encodeToString(input, 0, input.length, flags); }

    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        byte[] slice = java.util.Arrays.copyOfRange(input, offset, offset + len);
        java.util.Base64.Encoder enc = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) enc = enc.withoutPadding();
        String s = enc.encodeToString(slice);
        if ((flags & NO_WRAP) != 0) return s;
        String nl = (flags & CRLF) != 0 ? "\r\n" : "\n";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i += 76) b.append(s, i, Math.min(s.length(), i + 76)).append(nl);
        return b.toString();
    }
}
