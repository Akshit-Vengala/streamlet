package android.webkit;

import cloudstream.desktop.androidshim.ShimRuntime;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cookie store for plugins. Delegates to the JCEF-backed {@link ShimRuntime.CookieBridge}
 * (Track E) when installed, otherwise keeps cookies in memory per host.
 */
public class CookieManager {
    private static final CookieManager INSTANCE = new CookieManager();
    private final Map<String, Map<String, String>> jar = new ConcurrentHashMap<>();
    private boolean accept = true;

    protected CookieManager() { }

    public static CookieManager getInstance() { return INSTANCE; }

    private static String host(String url) {
        try {
            String h = URI.create(url.contains("://") ? url : "http://" + url).getHost();
            return h == null ? url : h;
        } catch (Exception e) { return url; }
    }

    public String getCookie(String url) {
        ShimRuntime.CookieBridge b = ShimRuntime.cookieBridge();
        if (b != null) return b.getCookie(url);
        Map<String, String> m = jar.get(host(url));
        if (m == null || m.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public void setCookie(String url, String cookie) {
        ShimRuntime.CookieBridge b = ShimRuntime.cookieBridge();
        if (b != null) { b.setCookie(url, cookie); return; }
        if (cookie == null) return;
        String pair = cookie.split(";", 2)[0];
        int i = pair.indexOf('=');
        if (i <= 0) return;
        jar.computeIfAbsent(host(url), k -> new ConcurrentHashMap<>()).put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
    }

    public void setCookie(String url, String cookie, ValueCallback<Boolean> callback) {
        setCookie(url, cookie);
        if (callback != null) callback.onReceiveValue(true);
    }

    public boolean hasCookies() { return !jar.isEmpty(); }
    public void setAcceptCookie(boolean accept) { this.accept = accept; }
    public boolean acceptCookie() { return accept; }
    public void setAcceptThirdPartyCookies(WebView view, boolean accept) { }
    public void flush() { }

    public void removeAllCookies(ValueCallback<Boolean> callback) {
        ShimRuntime.CookieBridge b = ShimRuntime.cookieBridge();
        if (b != null) b.removeAll();
        jar.clear();
        if (callback != null) callback.onReceiveValue(true);
    }

    public void removeAllCookie() { removeAllCookies(null); }
    public void removeSessionCookies(ValueCallback<Boolean> callback) { if (callback != null) callback.onReceiveValue(false); }
}
