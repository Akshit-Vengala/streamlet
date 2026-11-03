package android.webkit;

public class ConsoleMessage {
    private final String message;
    public ConsoleMessage() { this("", "", 0, null); }
    public ConsoleMessage(String message, String sourceId, int lineNumber, Object level) { this.message = message; }
    public String message() { return message == null ? "" : message; }
    public String sourceId() { return ""; }
    public int lineNumber() { return 0; }
}
