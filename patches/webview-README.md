# webview-* patches (Track E)

## webview-WebViewResolver-override
Upstream `library/src/jvmMain/.../network/WebViewResolver.jvm.kt` is the `actual class WebViewResolver`
for JVM and is a **no-op passthrough** (`resolveUsingWebView` is `TODO()`). `upstream/` is a pinned
submodule and must not be edited, so we do NOT patch the source. Instead:

- `webview-desktop` ships a class with the **identical FQN and public signature**:
  `com.lagradost.cloudstream3.network.WebViewResolver`
  (`webview-desktop/src/main/kotlin/com/lagradost/cloudstream3/network/WebViewResolver.kt`),
  backed by JCEF. Constructor/default-argument order, the three `resolveUsingWebView` overloads and the
  companion (`DEFAULT_TIMEOUT`, `webViewUserAgent`, `getWebViewUserAgent()` with Android's
  `@JvmName("getWebViewUserAgent1")`) match the Android actual, so dex2jar'd plugins link unchanged.
- **Override mechanism = classpath shadowing.** The JVM resolves the first `WebViewResolver.class` on the
  classpath. Therefore `webview-desktop.jar` MUST appear **before** `library-jvm-*.jar` on the class
  loader that hosts the plugins' parent (and the app). Rules for consumers:
  - desktop-app / plugin-loader: put `:webview-desktop` ahead of `:core` in the runtime classpath
    (Gradle: list the `webview-desktop` dependency before `core`; verify with
    `WebViewResolver::class.java.protectionDomain.codeSource`). In a custom `URLClassLoader` parent,
    order the URLs the same way.
  - Safer alternative the coordinator may adopt in `:core`/packaging: a Jar task that drops
    `com/lagradost/cloudstream3/network/WebViewResolver*.class` from the repackaged library jar, so no
    ordering is needed. (Not done here: root/core files are not Track E's to edit.)
  - Guard: `FakeEngineTest.webViewResolverShadowsLibraryClass` fails if the library's class wins.
- Compiling our copy next to the library jar emits no error: source classes win over classpath classes.

## webview-CloudflareKiller
`CloudflareKiller` lives in the Android-only `app` module upstream, so there is nothing to override;
`webview-desktop` simply provides `com.lagradost.cloudstream3.network.CloudflareKiller` with the same API
(`savedCookies`, `getCookieHeaders`, `parseCookieMap`, `intercept`). Android `CookieManager` is replaced by the
JCEF cookie store. Track C must NOT also provide this class.

## Known behavioural differences vs Android
- `useOkhttp` is accepted but ignored (CEF uses its own network stack).
- Only GET navigations are supported (same as Android `WebView.loadUrl`); custom headers/referer are applied to the
  first main-frame request.
- `script` is evaluated after each main-frame load (Android: on every intercepted request); the callback receives
  the JSON-encoded result, same as `evaluateJavascript`.
- Cookies solved in the browser are pushed into NiceHttp via `SharedCookieJar`, installed on `app.baseClient`
  on first use (NiceHttp's default client has no cookie jar). `insecureApp` is not touched; call
  `SharedCookieJar.install(insecureApp)` if needed.
