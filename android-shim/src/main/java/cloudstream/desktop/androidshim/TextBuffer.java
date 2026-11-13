package cloudstream.desktop.androidshim;

/** Minimal [android.text.Editable]: the text an EditText holds, so getText() is never null. */
public final class TextBuffer implements android.text.Editable {
    private final StringBuilder sb = new StringBuilder();

    public TextBuffer(CharSequence initial) { if (initial != null) sb.append(initial); }

    public void set(CharSequence s) { sb.setLength(0); if (s != null) sb.append(s); }

    @Override public int length() { return sb.length(); }
    @Override public char charAt(int index) { return sb.charAt(index); }
    @Override public CharSequence subSequence(int start, int end) { return sb.subSequence(start, end); }
    @Override public String toString() { return sb.toString(); }
}
