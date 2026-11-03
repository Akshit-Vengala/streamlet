package android.webkit;

public class WebChromeClient {
    public WebChromeClient() { }
    public boolean onConsoleMessage(ConsoleMessage message) { return false; }
    public void onReceivedTitle(WebView view, String title) { }
    public void onProgressChanged(WebView view, int newProgress) { }
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) { return false; }
}
