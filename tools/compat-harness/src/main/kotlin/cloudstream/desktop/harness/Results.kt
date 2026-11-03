package cloudstream.desktop.harness

/** Turns a child's event file + process outcome into a [PluginResult]. Pure; unit-tested with fixture events. */
object Results {
    fun parseEvents(lines: List<String>): List<Event> =
        lines.filter { it.isNotBlank() }.mapNotNull { runCatching { mapper.readValue(it, Event::class.java) }.getOrNull() }

    fun build(
        info: PluginRef,
        events: List<Event>,
        exitCode: Int?,
        timedOut: Boolean,
        totalMs: Long,
        logTail: String?,
        knownBroken: String?,
        priority: Boolean,
    ): PluginResult {
        var convert: StageResult? = null
        var load: StageResult? = null
        var warnings = emptyList<String>()
        var extractors = emptyList<String>()
        var hits = emptyList<String>()
        var fatal: ErrorInfo? = null
        var done = false
        val providers = LinkedHashMap<String, MutableList<StageResult>>()
        val pending = LinkedHashMap<String, String>() // provider -> stage started, not finished

        for (e in events) when (e.t) {
            "convert" -> {
                warnings = e.list.orEmpty()
                convert = if (e.ok == true) StageResult("convert", Status.PASS, e.ms ?: 0, detail = "${warnings.size} warnings")
                else StageResult("convert", Status.FAIL, e.ms ?: 0, error = e.error, bucket = e.error?.bucket ?: Bucket.DEX2JAR.id)
            }
            "load" -> {
                if (e.ok == true) {
                    val apis = e.list.orEmpty()
                    extractors = apis.filter { it.startsWith("extractor:") }.map { it.removePrefix("extractor:") }
                    val provs = apis.filterNot { it.startsWith("extractor:") }
                    provs.forEach { providers.getOrPut(it) { ArrayList() } }
                    load = StageResult("load-plugin", Status.PASS, e.ms ?: 0, detail = "providers=${provs.size} extractors=${extractors.size} ${e.detail ?: ""}".trim())
                } else load = StageResult("load-plugin", Status.FAIL, e.ms ?: 0, error = e.error, bucket = e.error?.bucket ?: Bucket.CORE.id)
            }
            "start" -> pending[e.provider!!] = e.stage!!
            "stage" -> {
                pending.remove(e.provider!!)
                providers.getOrPut(e.provider) { ArrayList() } += StageResult(
                    e.stage!!, e.status!!, e.ms ?: 0, e.detail, e.error, if (e.status == Status.FAIL) e.error?.bucket else null,
                )
            }
            "hits" -> hits = e.list.orEmpty()
            "fatal" -> fatal = e.error
            "done" -> done = true
        }

        val died = if (timedOut) Bucket.TIMEOUT else Bucket.CRASH
        val deathError = ErrorInfo(
            cls = if (timedOut) "HardTimeout" else "ProcessDied",
            message = if (timedOut) "killed after wall-clock limit" else "exit=$exitCode" + (oomHint(logTail)?.let { " ($it)" } ?: ""),
            bucket = died.id,
        )
        var processFailure: ErrorInfo? = fatal
        if (!done && fatal == null) {
            when {
                convert == null -> convert = StageResult("convert", Status.FAIL, totalMs, error = deathError, bucket = died.id)
                convert.status == Status.PASS && load == null ->
                    load = StageResult("load-plugin", Status.FAIL, totalMs, error = deathError, bucket = died.id)
                pending.isNotEmpty() -> pending.forEach { (p, s) ->
                    providers.getOrPut(p) { ArrayList() } += StageResult(s, Status.FAIL, totalMs, error = deathError, bucket = died.id)
                }
                convert.status == Status.PASS && load?.status == Status.PASS -> processFailure = deathError
                else -> processFailure = deathError // e.g. died after a failed load while dumping hits
            }
        }
        return PluginResult(
            internalName = info.internalName, name = info.name, repo = info.repo, version = info.version, tvTypes = info.tvTypes,
            priority = priority, knownBroken = knownBroken,
            convert = convert, load = load,
            providers = providers.map { ProviderResult(it.key, it.value) },
            extractors = extractors, shimHits = hits, warnings = warnings.take(20),
            exitCode = exitCode, totalMs = totalMs, processFailure = processFailure,
            logTail = if (!done || processFailure != null) logTail else null,
        )
    }

    private fun oomHint(log: String?) = if (log != null && log.contains("OutOfMemoryError")) "OutOfMemoryError" else null
}

data class PluginRef(val internalName: String, val name: String, val repo: String, val version: Int, val tvTypes: List<String>)
