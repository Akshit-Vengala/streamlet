package android.webkit;

/** Interface on Android (plugins call it with invokeinterface). */
public interface WebResourceRequest {
    android.net.Uri getUrl();
    String getMethod();
    java.util.Map<String, String> getRequestHeaders();
    boolean isForMainFrame();
    boolean hasGesture();
    boolean isRedirect();
}
