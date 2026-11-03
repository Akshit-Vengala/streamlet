package cloudstream.desktop.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fakes / fixtures only: nothing here executes plugin code or touches the network. */
class HarnessTest {
    private fun bucket(t: Throwable) = Classifier.describe(t).bucket

    @Test fun classifiesLinkageErrors() {
        assertEquals("missing-shim-symbol", bucket(NoClassDefFoundError("android/view/View")))
        assertEquals("missing-shim-symbol", bucket(NoSuchMethodError("a.B.c()V")))
        assertEquals("missing-shim-symbol", bucket(AbstractMethodError("x")))
        assertEquals("webview-or-cloudflare", bucket(NoClassDefFoundError("android/webkit/WebView")))
        assertEquals("android.view.View", Classifier.describe(NoClassDefFoundError("android/view/View (wrong name)")).symbol)
    }

    @Test fun wrappedLinkageStillMissingSymbol() {
        val t = RuntimeException("boom", java.lang.reflect.InvocationTargetException(NoClassDefFoundError("a/B")))
        val e = Classifier.describe(t)
        assertEquals("missing-shim-symbol", e.bucket)
        assertEquals("a.B", e.symbol)
        assertEquals("java.lang.NoClassDefFoundError", e.rootCls)
    }

    @Test fun classifiesOthers() {
        assertEquals("dex2jar-conversion", bucket(VerifyError("bad")))
        assertEquals("dex2jar-conversion", bucket(ClassFormatError("big")))
        assertEquals("external", bucket(java.net.UnknownHostException("nope.example")))
        assertEquals("external", bucket(java.net.SocketTimeoutException("t")))
        assertEquals("external", bucket(java.io.IOException("Unexpected code 503")))
        assertEquals("timeout", bucket(java.util.concurrent.TimeoutException("x")))
        assertEquals("webview-or-cloudflare", bucket(IllegalStateException("Just a moment... cloudflare")))
        assertEquals("core-behavior", bucket(NullPointerException("x")))
        assertEquals("core-behavior", bucket(IllegalArgumentException("bad json")))
    }

    @Test fun firstFrameSkipsJdk() {
        val e = Classifier.describe(RuntimeException("x"))
        assertTrue(e.frame!!.contains("HarnessTest"))
    }

    @Test fun describeFromLoaderString() {
        val e = Classifier.describe("java.lang.NoClassDefFoundError: android/app/Foo", listOf("android.app.Foo"))
        assertEquals("missing-shim-symbol", e.bucket)
        assertEquals("android.app.Foo", e.symbol)
    }

    private val ref = PluginRef("P", "P", "r", 1, emptyList())
    private fun ev(vararg s: String) = Results.parseEvents(s.toList())

    @Test fun fullPass() {
        val events = ev(
            """{"t":"convert","ok":true,"ms":10,"list":[]}""",
            """{"t":"load","ok":true,"ms":5,"list":["Prov","extractor:Ex"]}""",
            """{"t":"start","provider":"Prov","stage":"search"}""",
            """{"t":"stage","provider":"Prov","stage":"search","status":"PASS","ms":9}""",
            """{"t":"hits","list":["a"]}""", """{"t":"done"}""",
        )
        val r = Results.build(ref, events, 0, false, 100, null, null, false)
        assertTrue(r.passed()); assertEquals(listOf("Ex"), r.extractors); assertNull(r.bucket())
        assertEquals("pass", Report.cell(r, "search")); assertEquals("-", Report.cell(r, "loadLinks"))
    }

    @Test fun hungStageBecomesTimeout() {
        val events = ev(
            """{"t":"convert","ok":true,"ms":10}""", """{"t":"load","ok":true,"ms":5,"list":["Prov"]}""",
            """{"t":"start","provider":"Prov","stage":"getMainPage"}""",
        )
        val r = Results.build(ref, events, null, true, 600_000, "log", null, false)
        assertEquals("timeout", r.bucket()); assertEquals("getMainPage", r.firstFailure()!!.stage)
    }

    @Test fun diedMidwayIsCrashAndNoEventsIsConvertCrash() {
        val dead = Results.build(ref, ev("""{"t":"convert","ok":true}""", """{"t":"load","ok":true,"list":["Prov"]}""",
            """{"t":"start","provider":"Prov","stage":"search"}"""), 1, false, 5, "System.exit", null, false)
        assertEquals("crash", dead.bucket())
        val none = Results.build(ref, emptyList(), 1, false, 5, "OutOfMemoryError: Java heap space", null, false)
        assertEquals("crash", none.bucket()); assertTrue(none.convert!!.error!!.message!!.contains("OutOfMemoryError"))
    }

    @Test fun failedLoadKeepsBucketAndSymbol() {
        val err = """{"cls":"java.lang.NoClassDefFoundError","symbol":"a.B","bucket":"missing-shim-symbol"}"""
        val r = Results.build(ref, ev("""{"t":"convert","ok":true}""", """{"t":"load","ok":false,"error":$err}""", """{"t":"done"}"""), 0, false, 5, null, null, false)
        assertEquals("missing-shim-symbol", r.bucket()); assertEquals("a.B", r.firstFailure()!!.error!!.symbol)
    }

    @Test fun diffAndMarkdown() {
        val ok = Results.build(ref, ev("""{"t":"convert","ok":true}""", """{"t":"load","ok":true,"list":["Prov"]}""",
            """{"t":"stage","provider":"Prov","stage":"search","status":"PASS"}""", """{"t":"done"}"""), 0, false, 1, null, null, true)
        val bad = Results.build(ref, ev("""{"t":"convert","ok":true}""", """{"t":"load","ok":true,"list":["Prov"]}""",
            """{"t":"stage","provider":"Prov","stage":"search","status":"FAIL","error":{"cls":"X","bucket":"external"}}""", """{"t":"done"}"""), 0, false, 1, null, null, true)
        val a = Run("t1", listOf("https://raw.githubusercontent.com/u/r/b/x.json"), listOf(ok))
        val b = Run("t2", a.repos, listOf(bad))
        val d = Report.diff(a, b)
        assertEquals(1, d.size); assertTrue(d[0].startsWith("CHANGED P: pass -> external"))
        assertNotNull(Report.markdown(b, d, "cmd").takeIf { it.contains("## Failure buckets") && it.contains("external") })
        // round trip of the json
        assertEquals(b.plugins[0].name, mapper.readValue(mapper.writeValueAsString(b), Run::class.java).plugins[0].name)
    }
}
