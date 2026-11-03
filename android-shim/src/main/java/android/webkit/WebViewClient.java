package android.webkit;

/** Hand-written: callbacks are fired by WebView from the real browser. */
public class WebViewClient {
    public WebViewClient() { }
    public void onPageFinished(WebView view, String url) { }
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { }
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return null; }
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) { return null; }
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
    public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }
    public void onLoadResource(WebView view, String url) { }
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) { }
    public void onReceivedSslError(WebView view, Object handler, Object error) { }
}
