package io.bigmoeonedge.example

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    current: AppSettings,
    npuUnavailable: String?,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val ui by RunBus.state.collectAsStateWithLifecycle()
    val thinkingLocked = ui.thinkControl == "none"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val stream = !current.mmap
        val cacheOn = current.cacheMb == AppSettings.CACHE_AUTO || current.cacheMb > 0
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Streaming") {
                SwitchRow(
                    "mmap baseline (no streaming)",
                    "Load the model the ordinary way. The baseline to compare against.",
                    current.mmap,
                ) { onChange(current.copy(mmap = it)) }
                IntSetting(
                    "Expert cache (MiB)", AppSettings.CACHE_CHOICES, current.cacheMb,
                    format = {
                        when (it) {
                            AppSettings.CACHE_AUTO -> "Auto"
                            0 -> "off"
                            else -> "$it MiB"
                        }
                    },
                    enabled = stream,
                ) { onChange(current.copy(cacheMb = it)) }
                Hint(
                    "A resident expert costs no read, so a bigger cache means less waiting on flash, " +
                        "paid for in RAM. Auto sizes it once at load. The smallest rungs sit below the " +
                        "engine's floor and only churn."
                )
                IntSetting(
                    "Auto cache ceiling (MiB)", AppSettings.CACHE_CEIL_CHOICES, current.cacheCeilMb,
                    format = { if (it == 0) "no cap" else "$it MiB" },
                    enabled = stream && current.cacheMb == AppSettings.CACHE_AUTO,
                ) { onChange(current.copy(cacheCeilMb = it)) }
                Hint(
                    "Caps what Auto may claim. The system counts our own mapped weights as free, so " +
                        "uncapped it can ask for more than exists."
                )
                IntSetting("Parallel I/O lanes", AppSettings.IO_CHOICES, current.ioThreads, enabled = stream) {
                    onChange(current.copy(ioThreads = it))
                }
                Hint("Expert reads in flight at once. Helps only until the flash saturates.")
                SwitchRow(
                    "Direct I/O (O_DIRECT)",
                    "Bypass the page cache, so the system keeps no second copy of what the cache " +
                        "already holds. Falls back where unsupported.",
                    current.oDirect, enabled = stream,
                ) { onChange(current.copy(oDirect = it)) }
                SwitchRow(
                    "I/O and compute overlap",
                    "Issue the next reads while the current layer computes, so flash latency hides " +
                        "behind the work.",
                    current.overlap, enabled = stream,
                ) { onChange(current.copy(overlap = it)) }
                LabeledDropdown(
                    "Dense weights",
                    DenseWeights.values().map { it.label },
                    current.denseWeights.ordinal,
                    enabled = stream,
                ) { onChange(current.copy(denseWeights = DenseWeights.values()[it])) }
                Hint(current.denseWeights.blurb)
                SwitchRow(
                    "Stream row-gathered tables",
                    "A dense table the model only reads a few ROWS from per token (the token " +
                        "embedding) does not need to be in RAM: only the rows are read, from flash. " +
                        "Lossless - the output is identical. Which tables qualify is read off the " +
                        "graph at load, so on a model where none do this does nothing.",
                    current.rowStream,
                    enabled = stream,
                ) { onChange(current.copy(rowStream = it)) }
                SwitchRow(
                    "Release the model mapping",
                    "Once every weight has been copied into the app's own memory, the model file " +
                        "does not need to stay mapped. Handing the mapping back frees the kernel " +
                        "from tracking it, which showed up as less CPU per token. Lossless - the " +
                        "output is identical. Needs Dense weights on Anon or Pinned.",
                    current.releaseMmap,
                    enabled = stream && (current.denseWeights == DenseWeights.ANON ||
                        current.denseWeights == DenseWeights.AHWB),
                ) { onChange(current.copy(releaseMmap = it)) }
                ExperimentalGroup {
                    IntSetting(
                        "Temporal prefetch (layers)", AppSettings.PREFETCH_CHOICES, current.prefetchLayers,
                        format = { if (it == 0) "off" else "$it" },
                        enabled = stream && cacheOn && !current.predictPrefetch && current.routeAhead == 0,
                    ) { onChange(current.copy(prefetchLayers = it)) }
                    Hint("Bets a layer reuses the previous token's experts and reads them on idle lanes. Needs the cache.")
                    SwitchRow(
                        "Predictive prefetch",
                        "Runs the next layer's own router a layer early and prefetches what it names. " +
                            "More accurate than the bet above, and replaces it. Needs the cache.",
                        current.predictPrefetch,
                        enabled = stream && cacheOn && current.prefetchLayers == 0 && current.routeAhead == 0,
                    ) { onChange(current.copy(predictPrefetch = it)) }
                    if (current.predictPrefetch) {
                        IntSetting(
                            "Predicted misses to read ahead", AppSettings.PREDICT_SPEC_CHOICES,
                            current.predictSpecMax,
                            format = { if (it == 0) "retention only" else "$it" },
                            enabled = stream && cacheOn,
                        ) { onChange(current.copy(predictSpecMax = it)) }
                        Hint(
                            "Retention only reads nothing and just protects what the prediction names, " +
                                "which is the safe setting: reading ahead competes with the token now."
                        )
                    }
                }
            }
            Section("Speed / quality") {
                IntSetting(
                    "Drop cold experts (% of even share)", AppSettings.DROP_COLD_CHOICES, current.dropColdPct,
                    format = {
                        when (it) {
                            0 -> "off"
                            50 -> "50% (barely bites)"
                            75 -> "75% (recommended)"
                            100 -> "100% (fastest, roughest)"
                            else -> "$it%"
                        }
                    },
                    enabled = stream && cacheOn,
                ) { onChange(current.copy(dropColdPct = it)) }
                Hint(
                    "Skips a routed expert only when it is a cache miss and the router barely wanted " +
                        "it, so quality is spent only where it buys a read. A resident expert always " +
                        "runs and the top one is never dropped. Changes the reply, and not the same " +
                        "way twice: it depends on what the cache held."
                )
                val topk = ui.nExpertUsed
                if (current.dropColdPct > 0 && topk != null && topk in 1..4) {
                    Text(
                        "This model routes very few experts per token, so the same share covers much " +
                            "more of the reply. Check the answers, or turn this off here.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    )
                }
                IntSetting(
                    "Active experts (top-k)", AppSettings.N_EXPERT_CHOICES, current.nExpertUsed,
                    format = { if (it == 0) "model default" else "$it" },
                ) { onChange(current.copy(nExpertUsed = it)) }
                Hint("Consult fewer experts per token than the model asks for. Cuts compute and reads together, and changes the reply.")
                ExperimentalGroup {
                    IntSetting(
                        "Prefer cached experts (% of score range)", AppSettings.SUBSTITUTE_CHOICES,
                        current.substitutePct,
                        format = { if (it == 0) "off" else "$it%" },
                        enabled = stream && cacheOn,
                    ) { onChange(current.copy(substitutePct = it)) }
                    Hint(
                        "When two experts score close, picks the one already in RAM. Same number of " +
                            "experts, fewer flash reads, faster decode. Changes the reply; 15% is the " +
                            "measured sweet spot."
                    )
                    if (current.substitutePct >= 20) {
                        Text(
                            "Past 15% the model degrades faster than its replies show. Judge it on " +
                                "answers you can check, not on how fluent it sounds.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    LabeledDropdown(
                        "Guess ahead",
                        listOf("Off", "Model's own head (MTP)", "Repeated text (n-gram)"),
                        AppSettings.SPEC_CHOICES.indexOf(current.spec).coerceAtLeast(0),
                        enabled = current.routeAhead == 0,
                    ) { onChange(current.copy(spec = AppSettings.SPEC_CHOICES[it])) }
                    Hint(
                        "Draft the next few tokens, verify the group in one decode, keep only what the " +
                            "model would have produced. Lossless. Wins by reading the weights once for " +
                            "several tokens, loses when the wider verify makes each layer touch more " +
                            "experts. The head is accurate but only some models carry it; the n-gram " +
                            "lookup is free and works on any model, but only fires on repeated text."
                    )
                    if (current.spec != AppSettings.SPEC_OFF) {
                        IntSetting("Tokens guessed per pass", AppSettings.MTP_DRAFT_CHOICES, current.mtpDraft) {
                            onChange(current.copy(mtpDraft = it))
                        }
                        Hint(
                            "Wider means more tokens per decode, but drafts get less reliable and a " +
                                "rejected one is paid for anyway. The best value is rarely the largest."
                        )
                    }
                    if (current.spec == AppSettings.SPEC_MTP) {
                        IntSetting(
                            "Guess only when confident", AppSettings.MTP_P_MIN_CHOICES, current.mtpPMinPct,
                            format = { if (it == 0) "always draft" else "above $it%" },
                        ) { onChange(current.copy(mtpPMinPct = it)) }
                        Hint("Stop drafting once the head is unsure. A draft not made keeps the verify batch narrow, so fewer experts are read.")
                    }
                    IntSetting(
                        "Route-ahead (layers)", AppSettings.ROUTE_AHEAD_CHOICES, current.routeAhead,
                        format = { if (it == 0) "off" else "$it" },
                        enabled = stream && current.prefetchLayers == 0 && !current.predictPrefetch &&
                            current.spec == AppSettings.SPEC_OFF,
                    ) { onChange(current.copy(routeAhead = it)) }
                    Hint(
                        "Commits each layer's routing that many layers early, so its reads start early " +
                            "and can never be wasted. Lossy: some slots route differently. Excludes the " +
                            "prefetchers and Guess ahead."
                    )
                }
            }
            Section("Compute") {
                IntSetting("Compute threads", AppSettings.THREAD_CHOICES, current.threads) {
                    onChange(current.copy(threads = it))
                }
                IntegerInputSetting(
                    label = "Tokens to generate",
                    value = current.nPredict,
                    minValue = 1,
                    maxValue = 99999999,
                ) { onChange(current.copy(nPredict = it)) }
                IntegerInputSetting(
                    label = "Context (tokens)",
                    value = current.sessionCtx,
                    minValue = 512,
                    maxValue = 999999999,
                ) { onChange(current.copy(sessionCtx = it)) }
                Hint(
                    "Prompt plus reply the session can hold. Also memory: the KV cache is sized for it " +
                        "at open, and on a model that fills RAM that comes out of the expert cache. " +
                        "Changing it reopens the session."
                )
            }
            Section("NPU") {
                val npuOk = npuUnavailable == null
                SwitchRow(
                    "Prefill on the NPU (Snapdragon only)",
                    "Hexagon NPU only, Snapdragon 8 Gen 2 or newer. Runs the prompt on the NPU and keeps " +
                        "decoding on the CPU: long prompts start several times sooner. With streaming, the " +
                        "weights reach the NPU two layers at a time, straight from flash, and those two " +
                        "layers take about 1 GB that the expert cache no longer has, so decode is a little " +
                        "slower. Needs a Q4_K_M, Q4_0, Q8_0 or MXFP4 model (not Q3/Q2). Not identical to " +
                        "the CPU (the NPU computes in fp16). Ignored with speculation or row-streamed " +
                        "tables. If the NPU does not open, the prompt runs on the CPU.",
                    current.npuPrefill && npuOk,
                    enabled = npuOk,
                ) { onChange(current.copy(npuPrefill = it)) }
                if (npuUnavailable != null) Hint(npuUnavailable)
                IntSetting(
                    "NPU loader threads", AppSettings.NPU_LOADER_CHOICES, current.npuLoaders,
                    enabled = npuOk && current.npuPrefill && stream,
                ) { onChange(current.copy(npuLoaders = it)) }
            }
            Section("Prompt") {
                SwitchRow(
                    "Thinking",
                    if (thinkingLocked)
                        "This model always reasons and offers no way to turn it off, so the switch is " +
                            "disabled rather than ignored. Its reasoning still shows above the reply."
                    else
                        "Let a reasoning model think first; its reasoning shows in a block above the " +
                            "reply. No effect on models that do not reason.",
                    checked = current.thinking || thinkingLocked,
                    enabled = !thinkingLocked,
                ) { onChange(current.copy(thinking = it)) }
            }
            Section("Diagnostics") {
                SwitchRow(
                    "Metrics CSV",
                    "One CSV per session: per-token timings, faults, cache budget and where memory sat. " +
                        "Takes effect on the next session; share it from the menu.",
                    current.metricsCsv,
                ) { onChange(current.copy(metricsCsv = it)) }
            }
        }
    }
}

@Composable
private fun IntegerInputSetting(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    onSelect: (Int) -> Unit,
) {
    var input by remember(label, value) { mutableStateOf(value.toString()) }
    val parsed = input.toIntOrNull()
    val valid = parsed != null && parsed in minValue..maxValue

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { next ->
                if (next.length <= maxValue.toString().length && next.all { it in '0'..'9' }) {
                    input = next
                }
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            isError = !valid,
            supportingText = { Text("Range: $minValue–$maxValue") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = { parsed?.let(onSelect) }, enabled = valid) {
                Text("Apply")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
