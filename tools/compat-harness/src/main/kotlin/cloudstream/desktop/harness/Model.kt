package cloudstream.desktop.harness

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** Failure classes; Phase 3 fans out by these. Order = report order. */
enum class Bucket(val id: String, val blurb: String) {
    MISSING_SHIM("missing-shim-symbol", "NoClassDefFoundError / NoSuchMethodError / AbstractMethodError: shim or core lacks a symbol"),
    WEBVIEW("webview-or-cloudflare", "needs WebView / WebViewResolver / CloudflareKiller behaviour"),
    DEX2JAR("dex2jar-conversion", "conversion error, ClassFormatError or VerifyError"),
    CORE("core-behavior", "other exception inside library / plugin / shim code"),
    CRASH("crash", "child JVM died (System.exit, OOM, native crash)"),
    TIMEOUT("timeout", "stage or whole-plugin wall clock exceeded"),
    NO_RESULT("no-result", "stage ran but returned nothing"),
    EXTERNAL("external", "HTTP 4xx/5xx, DNS, geo-block, site down: not our bug"),
    HARNESS("harness", "harness / classpath setup error");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

enum class Status { PASS, FAIL, SKIP }

@JsonIgnoreProperties(ignoreUnknown = true)
data class ErrorInfo(
    val cls: String,
    val message: String? = null,
    /** First stack frame outside java./jdk./sun./kotlin. */
    val frame: String? = null,
    val rootCls: String? = null,
    val rootMessage: String? = null,
    val rootFrame: String? = null,
    /** Missing class / method for linkage errors. */
    val symbol: String? = null,
    val bucket: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StageResult(
    val stage: String,
    val status: Status,
    val ms: Long = 0,
    val detail: String? = null,
    val error: ErrorInfo? = null,
    val bucket: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProviderResult(val name: String, val stages: List<StageResult>)

/** One line of the child's event file. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Event(
    val t: String,
    val ok: Boolean? = null,
    val ms: Long? = null,
    val provider: String? = null,
    val stage: String? = null,
    val status: Status? = null,
    val detail: String? = null,
    val error: ErrorInfo? = null,
    val list: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginResult(
    val internalName: String,
    val name: String,
    val repo: String,
    val version: Int = 0,
    val priority: Boolean = false,
    val tvTypes: List<String> = emptyList(),
    val knownBroken: String? = null,
    val convert: StageResult? = null,
    val load: StageResult? = null,
    val providers: List<ProviderResult> = emptyList(),
    val extractors: List<String> = emptyList(),
    val shimHits: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val exitCode: Int? = null,
    val totalMs: Long = 0,
    /** Set when the child died / was killed. */
    val processFailure: ErrorInfo? = null,
    val logTail: String? = null,
) {
    fun allStages(): List<StageResult> = listOfNotNull(convert, load) + providers.flatMap { it.stages }

    /** First failing stage in pipeline order (convert, load, then per provider main/search/load/links). */
    fun firstFailure(): StageResult? = allStages().firstOrNull { it.status == Status.FAIL }

    /** Bucket id of the earliest failure, or null when everything that ran passed. */
    fun bucket(): String? = firstFailure()?.bucket ?: processFailure?.bucket

    fun passed(): Boolean = bucket() == null && convert?.status == Status.PASS && load?.status == Status.PASS
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Run(
    val startedAt: String,
    val repos: List<String>,
    val plugins: List<PluginResult>,
)

val STAGES = listOf("getMainPage", "search", "load", "loadLinks")
