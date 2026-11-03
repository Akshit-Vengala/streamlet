package cloudstream.desktop.androidshim;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Seam between the shimmed {@code android.webkit.WebView} and a real browser engine. The shim has no browser of its
 * own; the app installs a {@link Backend} (JCEF, from {@code :webview-desktop}) at start-up. Without one, WebViews
 * stay inert exactly like the old stubs.
 */
public final class WebViewBridge {
    private WebViewBridge() { }

    /** One browser instance behind one WebView. All methods may be called from any thread. */
    public interface Page {
        /** First call opens the browser; later calls navigate it. */
        void loadUrl(String url, Map<String, String> headers);
        /** Result is the JSON-encoded value (like Android's evaluateJavascript), or null if unavailable. */
        void evaluate(String js, Consumer<String> result);
        void setVisible(boolean visible);
        /** Mouse input from code (Android dispatchTouchEvent): 0 = down, 1 = up, 2 = move, at view coordinates. */
        default void mouse(int action, float x, float y) { }
        void stopLoading();
        void destroy();
    }

    /** Callbacks from the browser; the WebView forwards them to its WebViewClient / JS interfaces. */
    public interface Events {
        void onPageStarted(String url);
        void onPageFinished(String url);
        /** Return true to block the request. */
        boolean onRequest(String url, String method, Map<String, String> headers, boolean mainFrame);
        /** A JSON message from page script (addJavascriptInterface call). */
        void onBridgeMessage(String json);
    }

    public interface Backend {
        Page create(String userAgent, Events events);
    }

    private static volatile Backend backend;

    public static void setBackend(Backend b) { backend = b; }

    public static Backend backend() { return backend; }
}
