package io.bigmoeonedge.example

import android.content.Context

enum class DenseWeights(val flag: String, val label: String, val blurb: String) {
    MMAP("mmap", "Mmap (baseline)", "Left to the kernel, faulted in as touched. The baseline."),
    WARM("warm", "Warm at load", "Paged in once at load, so the first tokens do not fault. Best when the model fits in RAM."),
    ANON("anon", "Anon (O_DIRECT)", "Read into the app's own memory. Reclaim compresses them instead of dropping them, so a refault costs no flash read. The default."),
    AHWB("ahwb", "Pinned (dma-buf)", "As Anon, but reclaim cannot touch it at all, not even to compress. Pays off over a long conversation."),
}

data class AppSettings(
    val mmap: Boolean = false,
    val cacheMb: Int = 2000,
    val cacheCeilMb: Int = 3000,
    val ioThreads: Int = 4,
    val threads: Int = 4,
    val nExpertUsed: Int = 0,
    val nPredict: Int = DEFAULT_N_PREDICT,
    val sessionCtx: Int = SESSION_CTX,
    val oDirect: Boolean = true,
    val overlap: Boolean = true,
    val denseWeights: DenseWeights = DenseWeights.ANON,
    val prefetchLayers: Int = 0,
    val predictPrefetch: Boolean = false,
    val predictSpecMax: Int = 0,
    val routeAhead: Int = 0,
    val dropColdPct: Int = 75,
    val rowStream: Boolean = false,
    val releaseMmap: Boolean = false,
    val npuPrefill: Boolean = false,
    val npuLoaders: Int = 8,
    val substitutePct: Int = 0,
    val spec: String = SPEC_OFF,
    val mtpDraft: Int = 3,
    val mtpPMinPct: Int = 0,
    val thinking: Boolean = false,
    val metricsCsv: Boolean = true,
) {
    fun npuActive(): Boolean = npuPrefill && spec == SPEC_OFF && !(rowStream && !mmap)

    fun sessionArgv(cliPath: String, modelPath: String, csvPath: String? = null): List<String> {
        val a = mutableListOf(
            cliPath,
            "-m", modelPath,
            "-t", threads.toString(),
            "-c", sessionCtx.toString(),
            "--ubatch", minOf(if (npuActive()) NPU_UBATCH else SESSION_UBATCH, sessionCtx).toString(),
            "--chatml",
            "--session",
            "--decide",
        )
        if (nExpertUsed > 0) a += listOf("--n-expert-used", nExpertUsed.toString())
        if (metricsCsv && csvPath != null) a += listOf("--csv", csvPath)
        if (!mmap) {
            a += "--moe-stream"
            if (cacheMb == CACHE_AUTO) {
                a += listOf("--cache-mb", "auto")
                if (cacheCeilMb > 0) a += listOf("--cache-ceil-mb", cacheCeilMb.toString())
            } else {
                a += listOf("--cache-mb", cacheMb.toString())
                if (cacheNeedsForce(cacheMb)) a += "--force-cache"
            }
            a += listOf("--io-threads", ioThreads.toString())
            if (!oDirect) a += "--no-odirect"
            if (overlap) a += "--overlap"
            a += listOf("--dense-weights", denseWeights.flag)
            val cacheOn = cacheMb == CACHE_AUTO || cacheMb > 0
            if (prefetchLayers > 0 && cacheOn) a += listOf("--prefetch", prefetchLayers.toString())
            if (predictPrefetch && cacheOn && prefetchLayers == 0) {
                a += "--predict-prefetch"
                a += listOf("--predict-spec-max", predictSpecMax.toString())
            }
            if (routeAhead > 0 && prefetchLayers == 0 && !predictPrefetch && spec == SPEC_OFF) {
                a += listOf("--route-ahead", routeAhead.toString())
            }
            if (dropColdPct > 0 && cacheOn) {
                a += listOf("--drop-cold-experts", (dropColdPct / 100.0).toString())
            }
            if (rowStream) a += "--row-stream"
            if (releaseMmap && (denseWeights == DenseWeights.ANON || denseWeights == DenseWeights.AHWB)) {
                a += "--release-mmap"
            }
            if (substitutePct > 0 && cacheOn) {
                a += listOf("--expert-substitute", (substitutePct / 100.0).toString())
            }
        }
        if (npuActive()) {
            a += listOf("--prefill-device", NPU_DEVICE)
            if (!mmap) a += listOf("--prefill-loaders", npuLoaders.toString())
        }
        if (spec == SPEC_MTP) {
            a += listOf("--mtp", "--draft", mtpDraft.toString())
            if (mtpPMinPct > 0) a += listOf("--mtp-p-min", (mtpPMinPct / 100.0).toString())
        } else if (spec == SPEC_NGRAM) {
            a += listOf("--ngram", "--draft", mtpDraft.toString())
        }
        return a
    }

    fun sessionSignature(modelPath: String): String =
        sessionArgv(cliPath = "", modelPath = modelPath, csvPath = null).joinToString("|")

    fun save(ctx: Context) {
        ctx.prefs().edit()
            .putBoolean("mmap", mmap)
            .putInt("cacheMb", cacheMb).putInt("cacheCeilMb", cacheCeilMb)
            .putInt("ioThreads", ioThreads).putInt("threads", threads)
            .putInt("nExpertUsed", nExpertUsed)
            .putInt("nPredict", nPredict).putBoolean("oDirect", oDirect)
            .putBoolean("overlap", overlap)
            .putString("denseWeights", denseWeights.name)
            .putInt("prefetchLayers", prefetchLayers)
            .putBoolean("predictPrefetch", predictPrefetch)
            .putInt("predictSpecMax", predictSpecMax)
            .putInt("routeAhead", routeAhead)
            .putInt("dropColdPct", dropColdPct)
            .putBoolean("rowStream", rowStream)
            .putBoolean("releaseMmap", releaseMmap)
            .putBoolean("npuPrefill", npuPrefill)
            .putInt("npuLoaders", npuLoaders)
            .putInt("substitutePct", substitutePct)
            .putInt("sessionCtx", sessionCtx)
            .putString("spec", spec).putInt("mtpDraft", mtpDraft).putInt("mtpPMinPct", mtpPMinPct)
            .putBoolean("thinking", thinking)
            .putBoolean("metricsCsv", metricsCsv)
            .apply()
    }

    companion object {
        const val SESSION_CTX = 4096
        const val SESSION_UBATCH = 512
        const val NPU_UBATCH = 2048
        const val NPU_DEVICE = "HTP0"
        val NPU_LOADER_CHOICES = intArrayOf(2, 4, 6, 8, 12)
        val CTX_CHOICES = intArrayOf(512, 1024, 2048, 4096, 8192)
        const val DEFAULT_N_PREDICT = 1024
        const val SPEC_OFF = "off"
        const val SPEC_MTP = "mtp"
        const val SPEC_NGRAM = "ngram"
        val SPEC_CHOICES = arrayOf(SPEC_OFF, SPEC_MTP, SPEC_NGRAM)
        val MTP_DRAFT_CHOICES = intArrayOf(1, 2, 3, 4, 5)
        val MTP_P_MIN_CHOICES = intArrayOf(0, 40, 60, 80)

        fun newMetricsCsvPath(ctx: Context): String? {
            val dir = java.io.File(ctx.getExternalFilesDir(null) ?: return null, "metrics")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            return java.io.File(dir, "bmoe-$ts.csv").absolutePath
        }

        const val CACHE_AUTO = -1
        val CACHE_CHOICES = intArrayOf(CACHE_AUTO, 0, 500, 1000, 1250, 1500, 1750, 2000, 3000, 4000, 5000, 6000)
        fun cacheNeedsForce(mb: Int) = mb in 1 until CACHE_MIN_MB
        const val CACHE_MIN_MB = 1500
        val CACHE_CEIL_CHOICES = intArrayOf(0, 2000, 3000, 4000, 5000, 6000)
        val IO_CHOICES = intArrayOf(1, 2, 4, 8)
        val N_EXPERT_CHOICES = intArrayOf(0, 6, 4, 3, 2)
        val PREFETCH_CHOICES = intArrayOf(0, 1, 2, 4)
        val PREDICT_SPEC_CHOICES = intArrayOf(0, 1, 2, 4)
        val ROUTE_AHEAD_CHOICES = intArrayOf(0, 1, 2, 4)
        val DROP_COLD_CHOICES = intArrayOf(0, 50, 75, 100)
        val SUBSTITUTE_CHOICES = intArrayOf(0, 10, 15, 20, 30)
        val THREAD_CHOICES = intArrayOf(2, 4, 6, 8)
        val NPREDICT_CHOICES = intArrayOf(16, 32, 48, 64, 128, 256, 512, 1024, 2048)

        fun load(ctx: Context): AppSettings {
            val p = ctx.prefs()
            val d = AppSettings()
            return AppSettings(
                mmap = p.getBoolean("mmap", d.mmap),
                cacheMb = p.getInt("cacheMb", d.cacheMb),
                cacheCeilMb = p.getInt("cacheCeilMb", d.cacheCeilMb),
                ioThreads = p.getInt("ioThreads", d.ioThreads),
                threads = p.getInt("threads", d.threads),
                nExpertUsed = p.getInt("nExpertUsed", d.nExpertUsed),
                nPredict = p.getInt("nPredict", d.nPredict),
                oDirect = p.getBoolean("oDirect", d.oDirect),
                overlap = p.getBoolean("overlap", d.overlap),
                denseWeights = run {
                    val saved = p.getString("denseWeights", null)
                    when {
                        saved != null -> runCatching { DenseWeights.valueOf(saved) }.getOrDefault(d.denseWeights)
                        p.getBoolean("denseOdirect", false) -> DenseWeights.ANON
                        !p.getBoolean("warmDense", true) -> DenseWeights.MMAP
                        else -> d.denseWeights
                    }
                },
                prefetchLayers = p.getInt("prefetchLayers", d.prefetchLayers),
                predictPrefetch = p.getBoolean("predictPrefetch", d.predictPrefetch),
                predictSpecMax = p.getInt("predictSpecMax", d.predictSpecMax),
                routeAhead = p.getInt("routeAhead", d.routeAhead),
                dropColdPct = p.getInt("dropColdPct", d.dropColdPct),
                rowStream = p.getBoolean("rowStream", d.rowStream),
                releaseMmap = p.getBoolean("releaseMmap", d.releaseMmap),
                npuPrefill = p.getBoolean("npuPrefill", d.npuPrefill),
                npuLoaders = p.getInt("npuLoaders", d.npuLoaders),
                substitutePct = p.getInt("substitutePct", d.substitutePct),
                sessionCtx = p.getInt("sessionCtx", d.sessionCtx),
                spec = run {
                    val saved = p.getString("spec", null)
                    when {
                        saved != null && saved in SPEC_CHOICES -> saved
                        p.getBoolean("mtp", false) -> SPEC_MTP
                        else -> d.spec
                    }
                },
                mtpDraft = p.getInt("mtpDraft", d.mtpDraft),
                mtpPMinPct = p.getInt("mtpPMinPct", d.mtpPMinPct),
                thinking = p.getBoolean("thinking", d.thinking),
                metricsCsv = p.getBoolean("metricsCsv", d.metricsCsv),
            )
        }

        private fun Context.prefs() = getSharedPreferences("bmoe_settings", Context.MODE_PRIVATE)
    }
}
