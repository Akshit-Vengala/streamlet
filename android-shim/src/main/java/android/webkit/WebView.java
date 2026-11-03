// Desktop WebView: hand-written (generator skips it); other members are inert stubs.
package android.webkit;

public class WebView extends android.view.ViewGroup {
    // ---- Desktop implementation: delegates to a real browser through cloudstream.desktop.androidshim.WebViewBridge. ----
    private final WebSettings settings = new WebSettings();
    private final java.util.Map<String, Object> jsInterfaces = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile WebViewClient client = new WebViewClient();
    private volatile WebChromeClient chromeClient = new WebChromeClient();
    private volatile cloudstream.desktop.androidshim.WebViewBridge.Page page;
    private volatile String currentUrl;
    private volatile boolean destroyed;
    private android.content.Context ctx;

    public WebView(android.content.Context context) {
        super();
        this.ctx = context;
        cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.<init>");
    }

    private cloudstream.desktop.androidshim.WebViewBridge.Page page() {
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page;
        if (p != null || destroyed) return p;
        synchronized (this) {
            if (page == null) {
                cloudstream.desktop.androidshim.WebViewBridge.Backend b = cloudstream.desktop.androidshim.WebViewBridge.backend();
                if (b == null) return null;
                String ua = settings.getUserAgentString();
                // The engine's own identity needs no override (null = the browser's real user agent).
                page = b.create(WebSettings.ENGINE_USER_AGENT.equals(ua) ? null : ua, new Events());
            }
            return page;
        }
    }

    private final class Events implements cloudstream.desktop.androidshim.WebViewBridge.Events {
        @Override public void onPageStarted(String url) {
            dbg("page started " + url);
            currentUrl = url;
            onSecurityCheck = isSecurityCheckRequest(url);
            interstitial = false;
            pageSerial++;
            installInterfaces();
            try { client.onPageStarted(WebView.this, url, null); } catch (Throwable t) { warn("onPageStarted", t); }
        }
        @Override public void onPageFinished(String url) {
            dbg("page finished " + url);
            currentUrl = url;
            installInterfaces();
            try { client.onPageFinished(WebView.this, url); } catch (Throwable t) { warn("onPageFinished", t); }
            // Is this page Cloudflare's "Just a moment" interstitial? Shown if so, gone as soon as a normal page loads.
            checkInterstitial(0);
            checkInterstitial(1500);
        }
        @Override public boolean onRequest(String url, String method, java.util.Map<String, String> headers, boolean mainFrame) {
            if (isSecurityCheckRequest(url)) { onSecurityCheck = true; checkInterstitial(400); }
            try {
                client.shouldInterceptRequest(WebView.this, new DesktopWebResourceRequest(url, method, headers, mainFrame));
            } catch (Throwable t) { warn("shouldInterceptRequest", t); }
            return false;
        }
        @Override public void onBridgeMessage(String json) { dispatchBridge(json); }
    }

    private static void warn(String what, Throwable t) {
        System.err.println("[webview] " + what + " threw " + t);
    }

    /** -Dcloudstream.webview.debug=true: page loads, injected scripts and JS-bridge calls of plugin WebViews. */
    private static final boolean DEBUG = Boolean.getBoolean("cloudstream.webview.debug");
    private static void dbg(String s) { if (DEBUG) System.out.println("[webview] " + s); }

    /** Defines window.<name>.<method>(...) for every public method of each registered object. */
    private void installInterfaces() {
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page;
        if (p == null || jsInterfaces.isEmpty()) return;
        StringBuilder sb = new StringBuilder("(function(){");
        for (java.util.Map.Entry<String, Object> e : jsInterfaces.entrySet()) {
            String name = e.getKey();
            sb.append("var o={};");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (java.lang.reflect.Method m : e.getValue().getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class || !seen.add(m.getName())) continue;
                sb.append("o['").append(m.getName()).append("']=function(){window.cefQuery({request:JSON.stringify({__wv:1,i:'")
                  .append(name).append("',m:'").append(m.getName())
                  .append("',a:Array.prototype.slice.call(arguments).map(function(x){return x===null||x===undefined?null:String(x);})}),")
                  .append("onSuccess:function(){},onFailure:function(){}});};");
            }
            sb.append("window['").append(name).append("']=o;");
        }
        sb.append("return 1;})()");
        p.evaluate(sb.toString(), null);
    }

    private void dispatchBridge(String json) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            Object target = jsInterfaces.get(o.get("i").getAsString());
            if (target == null) return;
            String mname = o.get("m").getAsString();
            dbg("bridge " + o.get("i").getAsString() + "." + mname + " (" + o.getAsJsonArray("a").size() + " args)");
            com.google.gson.JsonArray a = o.getAsJsonArray("a");
            for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(mname) || m.getParameterCount() != a.size()) continue;
                Class<?>[] types = m.getParameterTypes();
                Object[] args = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    com.google.gson.JsonElement el = a.get(i);
                    String s = el.isJsonNull() ? null : el.getAsString();
                    if (types[i] == String.class || types[i] == Object.class || types[i] == CharSequence.class) args[i] = s;
                    else if (s == null) args[i] = null;
                    else if (types[i] == int.class || types[i] == Integer.class) args[i] = Integer.parseInt(s);
                    else if (types[i] == long.class || types[i] == Long.class) args[i] = Long.parseLong(s);
                    else if (types[i] == boolean.class || types[i] == Boolean.class) args[i] = Boolean.parseBoolean(s);
                    else if (types[i] == double.class || types[i] == Double.class) args[i] = Double.parseDouble(s);
                    else if (types[i] == float.class || types[i] == Float.class) args[i] = Float.parseFloat(s);
                    else args[i] = s;
                }
                m.setAccessible(true);
                m.invoke(target, args);
                return;
            }
        } catch (Throwable t) { warn("javascript interface call", t); }
    }

    public void addJavascriptInterface(Object obj, String name) {
        if (obj == null || name == null) return;
        jsInterfaces.put(name, obj);
        installInterfaces();
    }

    public void removeJavascriptInterface(String name) { jsInterfaces.remove(name); }

    public void loadUrl(String url) { loadUrl(url, java.util.Collections.<String, String>emptyMap()); }

    public void loadUrl(String url, java.util.Map<String, String> headers) {
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page();
        if (p == null) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.loadUrl(no backend)"); return; }
        currentUrl = url;
        if (url != null && url.startsWith("javascript:")) { p.evaluate(url.substring("javascript:".length()), null); return; }
        p.loadUrl(url, headers == null ? java.util.Collections.<String, String>emptyMap() : headers);
    }

    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {
        String u = "data:" + (mimeType == null ? "text/html" : mimeType) + ";base64,"
            + java.util.Base64.getEncoder().encodeToString((data == null ? "" : data).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        loadUrl(u);
    }

    public void evaluateJavascript(String script, ValueCallback<String> callback) {
        dbg("evaluate " + (script == null ? "null" : script.replaceAll("\\s+", " ").substring(0, Math.min(90, script.replaceAll("\\s+", " ").length()))));
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page();
        if (p == null) { if (callback != null) callback.onReceiveValue(null); return; }
        p.evaluate(script, callback == null ? null : callback::onReceiveValue);
    }

    public WebSettings getSettings() { return settings; }

    public void setWebViewClient(WebViewClient c) { client = c == null ? new WebViewClient() : c; }

    public void setWebChromeClient(WebChromeClient c) { chromeClient = c == null ? new WebChromeClient() : c; }

    public WebViewClient getWebViewClient() { return client; }

    public WebChromeClient getWebChromeClient() { return chromeClient; }

    public String getUrl() { return currentUrl; }

    public String getOriginalUrl() { return currentUrl; }

    public String getTitle() { return ""; }

    public void stopLoading() {
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page;
        if (p != null) p.stopLoading();
    }

    public void reload() { String u = currentUrl; if (u != null) loadUrl(u); }

    public boolean canGoBack() { return false; }

    public void goBack() { }

    public void clearCache(boolean includeDiskFiles) { }

    public void clearHistory() { }

    public void onResume() { }

    public void onPause() { }

    public void pauseTimers() { }

    public void resumeTimers() { }

    public void destroy() {
        destroyed = true;
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page;
        page = null;
        if (p != null) p.destroy();
    }

    /**
     * A plugin's simulated tap (some click the Turnstile checkbox this way): sent to the browser as real mouse
     * input at the same view coordinates (the off-screen browser has a device pixel ratio of 1).
     */
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e == null) return false;
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page;
        if (p == null) return false;
        int a = e.getActionMasked();
        dbg("touch " + a + " at " + e.getX() + "," + e.getY());
        if (a != android.view.MotionEvent.ACTION_DOWN && a != android.view.MotionEvent.ACTION_UP && a != android.view.MotionEvent.ACTION_MOVE) return false;
        p.mouse(a, e.getX(), e.getY());
        return true;
    }

    private static final java.util.concurrent.ScheduledExecutorService POSTER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webview-post"); t.setDaemon(true); return t;
    });

    /** View.post / postDelayed: run later (the tap's "finger up" arrives this way). */
    public boolean post(java.lang.Runnable r) { return postDelayed(r, 0); }

    public int getWidth() { return 1280; }

    public int getHeight() { return 800; }
    public WebView() { }
    public android.content.Context getContext() { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.getContext"); return cloudstream.desktop.androidshim.ShimRuntime.context(); }
    public android.os.Handler getHandler() { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.getHandler"); return null; }
    public android.view.ViewGroup.LayoutParams getLayoutParams() { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.getLayoutParams"); return new android.view.ViewGroup.LayoutParams(); }
    public android.view.ViewParent getParent() {
        Object p = cloudstream.desktop.androidshim.ViewTree.parent(this);
        return p instanceof android.view.ViewParent ? (android.view.ViewParent) p : null;
    }

    /**
     * Desktop: a dialog containing this WebView was shown (true) or dismissed (false) by the plugin, so its real
     * browser window appears or disappears - exactly when the WebView would be on screen on Android.
     */
    public void desktopSetOnScreen(boolean shown) {
        // A plugin dialog alone never shows a window (some plugins show dialogs with helper WebViews that need
        // no user). It does while the page has a Cloudflare check on it (a plugin's "click the checkbox" dialog).
        dialogShown = shown;
        updateWindow();
    }

    /** The current page loaded Cloudflare challenge / Turnstile resources (reset on each new page). */
    private volatile boolean onSecurityCheck;
    /** The current page is Cloudflare's interstitial ("Just a moment...", the managed challenge). */
    private volatile boolean interstitial;
    /** The plugin is showing a dialog containing this WebView. */
    private volatile boolean dialogShown;
    /** Our window is on screen. */
    private volatile boolean windowShown;
    private volatile int pageSerial;

    /**
     * The window is up exactly while a person is needed: on Cloudflare's interstitial page, or while the plugin's
     * dialog shows a page with a check on it. A normal page (the check passed) takes it away at once. Merely loading
     * Turnstile's script (sites embed it everywhere) is not a reason.
     */
    private void updateWindow() {
        if (destroyed) return;
        boolean want = interstitial || (dialogShown && onSecurityCheck);
        if (want == windowShown) return;
        cloudstream.desktop.androidshim.WebViewBridge.Page p = page;
        if (p == null) return;
        windowShown = want;
        dbg((want ? "showing" : "hiding") + " the window (interstitial=" + interstitial + ", dialog=" + dialogShown + ")");
        p.setVisible(want);
    }

    private static final String INTERSTITIAL_JS =
        "!!(window._cf_chl_opt || document.getElementById('challenge-form') || document.getElementById('challenge-running')"
        + " || /^(just a moment|attention required)/i.test(document.title || ''))";

    /** Asks the page (after [delayMs]) whether it is the interstitial, then shows / hides the window accordingly. */
    private void checkInterstitial(long delayMs) {
        final int serial = pageSerial;
        POSTER.schedule(() -> {
            cloudstream.desktop.androidshim.WebViewBridge.Page p = page;
            if (p == null || destroyed || serial != pageSerial) return;
            p.evaluate(INTERSTITIAL_JS, r -> {
                if (serial != pageSerial) return;
                interstitial = "true".equals(r);
                updateWindow();
            });
        }, Math.max(0, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static boolean isSecurityCheckRequest(String url) {
        if (url == null) return false;
        return url.contains("/cdn-cgi/challenge-platform/") || url.contains("challenges.cloudflare.com/");
    }
    public void onWindowVisibilityChanged(int a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.onWindowVisibilityChanged"); }
    public boolean postDelayed(java.lang.Runnable a0, long a1) {
        if (a0 == null) return false;
        POSTER.schedule(() -> { try { a0.run(); } catch (Throwable t) { warn("posted runnable", t); } }, Math.max(0, a1), java.util.concurrent.TimeUnit.MILLISECONDS);
        return true;
    }
    public boolean requestFocus() { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.requestFocus"); return false; }
    public void scrollBy(int a0, int a1) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.scrollBy"); }
    public void setAlpha(float a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setAlpha"); }
    public void setBackgroundColor(int a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setBackgroundColor"); }
    public void setClickable(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setClickable"); }
    public void setFocusable(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setFocusable"); }
    public void setFocusableInTouchMode(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setFocusableInTouchMode"); }
    public void setLayoutParams(android.view.ViewGroup.LayoutParams a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setLayoutParams"); }
    public void setVisibility(int a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setVisibility"); }
    public static void setWebContentsDebuggingEnabled(boolean a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.setWebContentsDebuggingEnabled"); }
    public static class WebViewTransport extends java.lang.Object {
        public WebViewTransport() { }
        public void setWebView(android.webkit.WebView a0) { cloudstream.desktop.androidshim.Stub.hit("android.webkit.WebView.WebViewTransport.setWebView"); }
    }
}
