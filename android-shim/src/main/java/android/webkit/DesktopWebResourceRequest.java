package android.webkit;

import java.util.Collections;
import java.util.Map;

/** Desktop implementation handed to WebViewClient.shouldInterceptRequest. */
final class DesktopWebResourceRequest implements WebResourceRequest {
    private final String url, method;
    private final Map<String, String> headers;
    private final boolean mainFrame;

    DesktopWebResourceRequest(String url, String method, Map<String, String> headers, boolean mainFrame) {
        this.url = url == null ? "" : url;
        this.method = method == null ? "GET" : method;
        this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
        this.mainFrame = mainFrame;
    }

    @Override public android.net.Uri getUrl() { return android.net.Uri.parse(url); }
    @Override public String getMethod() { return method; }
    @Override public Map<String, String> getRequestHeaders() { return headers; }
    @Override public boolean isForMainFrame() { return mainFrame; }
    @Override public boolean hasGesture() { return false; }
    @Override public boolean isRedirect() { return false; }
}
