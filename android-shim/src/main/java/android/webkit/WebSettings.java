// Hand-written (generator skips it): keeps the user agent, everything else is inert.
package android.webkit;

public class WebSettings {
    public WebSettings() { }

    /** The browser engine's real user agent (keep equal to webview-desktop's JcefRuntime.DEFAULT_USER_AGENT). */
    public static final String ENGINE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    public static java.lang.String getDefaultUserAgent(android.content.Context a0) { return ENGINE_USER_AGENT; }

    /**
     * Starts as the engine's real user agent, like an Android WebView's (plugins read it and derive their own: some
     * keeps a "Chrome/" one and only falls back to a canned Android Chrome 124 string when there is none).
     */
    private volatile String userAgent = ENGINE_USER_AGENT;
    /** What the page actually sends: the engine's own identity, or a custom one the engine can honestly back. */
    public java.lang.String getUserAgentString() { return userAgent; }

    /**
     * A custom user agent is used only if it describes this engine (same Chrome major, same platform, no "Mobile" on
     * a desktop engine). Anything else - a phone, an older Chrome, Firefox - is a claim Cloudflare checks against what
     * the engine actually does: the Turnstile solve then never sticks (endless "verify you are human"). The getter
     * keeps reporting what is really sent, so a plugin that reuses it (with the clearance cookie) stays consistent.
     */
    static boolean matchesEngine(String ua) {
        if (ua == null || ua.isEmpty()) return false;
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua);
        java.util.regex.Matcher e = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ENGINE_USER_AGENT);
        if (!a.find() || !e.find() || !a.group(1).equals(e.group(1))) return false;
        boolean sameOs = ua.contains("Windows NT") == ENGINE_USER_AGENT.contains("Windows NT") && ua.contains("Android") == ENGINE_USER_AGENT.contains("Android");
        return sameOs && ua.contains("Mobile") == ENGINE_USER_AGENT.contains("Mobile");
    }
    public void setAllowContentAccess(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setAllowContentAccess"); }
    public void setAllowFileAccess(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setAllowFileAccess"); }
    public void setBlockNetworkImage(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setBlockNetworkImage"); }
    public void setBuiltInZoomControls(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setBuiltInZoomControls"); }
    public void setCacheMode(int a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setCacheMode"); }
    public void setDatabaseEnabled(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setDatabaseEnabled"); }
    public void setDomStorageEnabled(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setDomStorageEnabled"); }
    public void setJavaScriptCanOpenWindowsAutomatically(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setJavaScriptCanOpenWindowsAutomatically"); }
    public void setJavaScriptEnabled(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setJavaScriptEnabled"); }
    public void setLoadWithOverviewMode(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setLoadWithOverviewMode"); }
    public void setLoadsImagesAutomatically(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setLoadsImagesAutomatically"); }
    public void setMediaPlaybackRequiresUserGesture(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setMediaPlaybackRequiresUserGesture"); }
    public void setMixedContentMode(int a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setMixedContentMode"); }
    public void setSupportMultipleWindows(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setSupportMultipleWindows"); }
    public void setSupportZoom(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setSupportZoom"); }
    public void setUseWideViewPort(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebSettings.setUseWideViewPort"); }
    public void setUserAgentString(java.lang.String a0) {
        if (a0 == null || a0.isEmpty()) { userAgent = ENGINE_USER_AGENT; return; } // Android: null/"" = the default
        if (matchesEngine(a0)) { userAgent = a0; return; }
        if (Boolean.getBoolean("cloudstream.webview.debug")) System.out.println("[webview] keeping the engine's user agent instead of: " + a0);
        userAgent = ENGINE_USER_AGENT;
    }
}
