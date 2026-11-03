package android.text;

/** Spans are accepted and ignored; text behaves like a StringBuilder. */
public class SpannableStringBuilder implements CharSequence, Appendable {
    private final StringBuilder sb = new StringBuilder();

    public SpannableStringBuilder() { }
    public SpannableStringBuilder(CharSequence text) { sb.append(text); }

    @Override public SpannableStringBuilder append(CharSequence text) { sb.append(text); return this; }
    @Override public SpannableStringBuilder append(CharSequence text, int start, int end) { sb.append(text, start, end); return this; }
    @Override public SpannableStringBuilder append(char c) { sb.append(c); return this; }
    public SpannableStringBuilder append(CharSequence text, Object what, int flags) {
        int s = sb.length();
        sb.append(text);
        setSpan(what, s, sb.length(), flags);
        return this;
    }

    public void setSpan(Object what, int start, int end, int flags) { /* spans unsupported on desktop */ }

    @Override public int length() { return sb.length(); }
    @Override public char charAt(int index) { return sb.charAt(index); }
    @Override public CharSequence subSequence(int start, int end) { return sb.subSequence(start, end); }
    @Override public String toString() { return sb.toString(); }
}
