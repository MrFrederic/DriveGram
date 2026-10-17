@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.drivegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Laid out after Google's Android settings guidelines (source.android.com/docs/core/settings/settings-guidelines):
// a short home list that shows each area's current values, one page per area, single choices in a dialog rather
// than radio buttons in the list, and the long model lists on their own searchable page.
// Styled as Material 3 Expressive: rows in segmented groups (see ListGroup), a large flexible top bar, shaped icons,
// connected toggle buttons for single choices and the expressive loading indicator.

private enum class Page(val title: String) { READING("Reading aloud"), REPLYING("Replying"), OPENROUTER("OpenRouter") }

private class Option(val id: String, val name: String, val detail: String)

/** A full-page searchable list (models, voices). [options] empty means there's nothing to list: type an ID. */
private class Picker(val title: String, val selected: String, val options: List<Option>, val emptyHint: String, val onPick: (String) -> Unit)

private class KeyStatus(val text: String, val ok: Boolean?)

/** Write-through Compose state for one setting, so every page sees a change at once. */
private fun <T> pref(get: () -> T, set: (T) -> Unit) = object : ReadWriteProperty<Any?, T> {
    val state = mutableStateOf(get())
    override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        set(value)
    }
}

private class UiSettings(s: Settings) {
    var lastN by pref({ s.lastN }) { s.lastN = it }
    var readAloudLlm by pref({ s.readAloudLlm }) { s.readAloudLlm = it }
    var ttsEngine by pref({ s.ttsEngine }) { s.ttsEngine = it }
    var llmModel by pref({ s.llmModel }) { s.llmModel = it }
    var ttsModel by pref({ s.ttsModel }) { s.ttsModel = it }
    var ttsVoice by pref({ s.ttsVoice }) { s.ttsVoice = it }
    var sttModel by pref({ s.sttModel }) { s.sttModel = it }
    var cleanUpDictation by pref({ s.cleanUpDictation }) { s.cleanUpDictation = it }
    var silenceSeconds by pref({ s.silenceSeconds }) { s.silenceSeconds = it }
    var autoSend by pref({ s.autoSend }) { s.autoSend = it }
}

@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val ui = remember { UiSettings(settings) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var hasKey by remember { mutableStateOf(settings.openRouterKey != null) }
    var keyVersion by remember { mutableIntStateOf(0) }
    val keyStatus = rememberKeyStatus(settings, hasKey, keyVersion)
    // null while loading or offline: the pickers then take a typed ID.
    var textModels by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var speechModels by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var transcriptionModels by remember { mutableStateOf<List<ModelInfo>?>(null) }
    LaunchedEffect(Unit) {
        textModels = runCatching { OpenRouter(null).models("text") }.getOrNull()
        speechModels = runCatching { OpenRouter(null).models("speech") }.getOrNull()
        transcriptionModels = runCatching { OpenRouter(null).models("transcription") }.getOrNull()
        fixVoice(ui, speechModels)
    }

    fun back() {
        when {
            picker != null -> picker = null
            page != null -> page = null
            else -> onBack()
        }
    }
    BackHandler(enabled = picker != null || page != null) { back() }

    fun modelPicker(title: String, selected: String, models: List<ModelInfo>?, onPick: (String) -> Unit) {
        picker = Picker(
            title, selected,
            models.orEmpty().map { Option(it.id, it.name, it.price) },
            "Couldn't load OpenRouter's model list. Type a model ID from openrouter.ai/models.",
            onPick,
        )
    }

    // Keyed on the page so each page opens with the title expanded.
    val barState = remember(page, picker) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(picker?.title ?: page?.title ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = ::back, shapes = IconButtonDefaults.shapes()) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        // imePadding: without it the keyboard covers the search field and the list can't scroll past it.
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
        val current = picker
        if (current != null) {
            PickerPage(current, modifier) { picker = null }
            return@Scaffold
        }
        Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (page) {
                null -> {
                    ListGroup(
                        { NavItem(it, R.drawable.ic_play, "Reading aloud", readingSummary(ui, hasKey)) { page = Page.READING } },
                        { NavItem(it, R.drawable.ic_mic, "Replying", replyingSummary(ui, hasKey)) { page = Page.REPLYING } },
                        {
                            NavItem(it, R.drawable.ic_key, "OpenRouter", if (hasKey) keyStatus.text else "No key: AI tidy-up, the OpenRouter voice and speech to text are off") {
                                page = Page.OPENROUTER
                            }
                        },
                    )
                    val uriHandler = LocalUriHandler.current
                    ListGroup({
                        NavItem(it, R.drawable.ic_info, "Licence", "PolyForm Noncommercial 1.0.0 · free to use, modify and share, no commercial use") {
                            uriHandler.openUri(LICENSE_URL)
                        }
                    })
                }

                Page.READING -> {
                    ListGroup(
                        { SliderItem(it, "Messages to read when nothing is unread", ui.lastN, 1..10, { "$it" }) { ui.lastN = it } },
                        {
                            SwitchItem(
                                it, "Tidy up text with AI", "Fixes typos and expands abbreviations so the voice sounds natural. Never changes the meaning.",
                                checked = ui.readAloudLlm && hasKey, enabled = hasKey,
                            ) { ui.readAloudLlm = it }
                        },
                    )
                    val openRouterVoice = ui.ttsEngine == Settings.TtsEngine.OPENROUTER && hasKey
                    Column {
                        SectionTitle("Voice")
                        ListGroup(
                            { EngineItem(it, ui, hasKey, openRouterVoice) },
                            if (openRouterVoice) { {
                                PickerItem(it, "Voice model", ui.ttsModel, speechModels) {
                                    modelPicker("Voice model", ui.ttsModel, speechModels) { picked ->
                                        ui.ttsModel = picked
                                        fixVoice(ui, speechModels)
                                    }
                                }
                            } } else null,
                            if (openRouterVoice) { {
                                val voices = speechModels?.find { m -> m.id == ui.ttsModel }?.voices.orEmpty()
                                PickerItem(it, "Voice", ui.ttsVoice, null) {
                                    picker = Picker("Voice", ui.ttsVoice, voices.map { v -> Option(v, v, "") }, "This model doesn't list its voices. Type a voice name.") { v ->
                                        ui.ttsVoice = v
                                    }
                                }
                            } } else null,
                            {
                                TestItem(it, "Test voice", "Plays a sample sentence with this voice.", enabled = true) {
                                    val lang = phoneLang()
                                    App.instance.reader.voice()
                                        .prepare(if (lang == "ru") "Так DriveGram будет читать ваши сообщения." else "This is how DriveGram reads your messages.", lang)
                                        .play()
                                    "Played"
                                }
                            },
                        )
                    }
                    if (!hasKey) KeyNeeded { page = Page.OPENROUTER }
                }

                Page.REPLYING -> {
                    val context = LocalContext.current
                    ListGroup(
                        {
                            SwitchItem(
                                it, "Tidy up dictation with AI", "Removes filler words and false starts. Keeps your words, style and language.",
                                checked = ui.cleanUpDictation && hasKey, enabled = hasKey,
                            ) { ui.cleanUpDictation = it }
                        },
                        {
                            SliderItem(
                                it, "Stop listening after silence", ui.silenceSeconds, 0..15, { s -> if (s == 0) "Off" else "$s s" },
                                note = "${if (ui.silenceSeconds == 0) "Only Done stops the recording." else "Done also stops it."} Recordings end after ${MAX_RECORDING_MS / 1000} s.",
                            ) { ui.silenceSeconds = it }
                        },
                        {
                            SwitchItem(
                                it, "Send without asking", "Skips Send / Cancel. The AI may have reworded what you said, so this is off unless you turn it on.",
                                checked = ui.autoSend, enabled = true,
                            ) { ui.autoSend = it }
                        },
                    )
                    ListGroup(
                        if (hasKey) { {
                            PickerItem(it, "Speech-to-text model", ui.sttModel, transcriptionModels) {
                                modelPicker("Speech-to-text model", ui.sttModel, transcriptionModels) { m -> ui.sttModel = m }
                            }
                        } } else null,
                        {
                            TestItem(it, "Test speech to text", "The phone voice says a sample sentence and the model writes it down.", enabled = hasKey) {
                                val file = java.io.File(context.cacheDir, "stt-test.wav")
                                App.instance.reader.phoneVoice.synthesizeToFile("Привет! Буду через десять минут, see you soon.", "ru", file)
                                "Heard: " + OpenRouterSpeechToText(OpenRouter(settings.openRouterKey), settings.sttModel).transcribe(file.readBytes())
                            }
                        },
                    )
                    if (!hasKey) KeyNeeded { page = Page.OPENROUTER }
                }

                Page.OPENROUTER -> {
                    val onHasKey: (Boolean) -> Unit = { has -> hasKey = has; keyVersion++ }
                    ListGroup(
                        { KeyItem(it, settings, hasKey, keyStatus, onHasKey) },
                        if (hasKey) { { RemoveKeyItem(it, settings, onHasKey) } } else null,
                    )
                    ListGroup(
                        {
                            PickerItem(it, "Tidy-up model", ui.llmModel, textModels, enabled = hasKey) {
                                modelPicker("Tidy-up model", ui.llmModel, textModels) { m -> ui.llmModel = m }
                            }
                        },
                        {
                            TestItem(it, "Test tidy-up", "Tidies up a sample message the way Play would.", enabled = hasKey) {
                                try {
                                    OpenRouterRewriter(OpenRouter(settings.openRouterKey), settings.llmModel, cached = false).prepareForSpeech(CLEAN_UP_SAMPLE, "ru")
                                } catch (e: RewriteRejected) {
                                    "${e.message}:\n${e.output}"
                                }
                            }
                        },
                    )
                    Footer("OpenRouter runs the AI tidy-up, the OpenRouter voice and speech to text. Get a key at openrouter.ai/keys.")
                }
            }
        }
    }
}

/** Voices are per model: keep yours if the model has it, else take its first one (another model's voice gets a 400). */
private fun fixVoice(ui: UiSettings, speechModels: List<ModelInfo>?) {
    val voices = speechModels?.find { it.id == ui.ttsModel }?.voices.orEmpty()
    if (voices.isNotEmpty() && ui.ttsVoice !in voices) ui.ttsVoice = voices.first()
}

private fun readingSummary(ui: UiSettings, hasKey: Boolean) = listOfNotNull(
    if (ui.ttsEngine == Settings.TtsEngine.OPENROUTER && hasKey) "OpenRouter voice" else "Phone voice",
    "AI tidy-up".takeIf { ui.readAloudLlm && hasKey },
    "last ${ui.lastN} when nothing is unread",
).joinToString(" · ")

private fun replyingSummary(ui: UiSettings, hasKey: Boolean) = listOfNotNull(
    if (ui.silenceSeconds == 0) "Stops when you tap Done" else "Stops after ${ui.silenceSeconds} s of silence",
    "AI tidy-up".takeIf { ui.cleanUpDictation && hasKey },
    if (ui.autoSend) "sends without asking" else "asks before sending",
).joinToString(" · ")

@Composable
private fun NavItem(shapes: ListItemShapes, icon: Int, title: String, summary: String, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        supportingContent = { Text(summary) },
        leadingContent = { ShapedIcon(icon, shape = MaterialShapes.Circle.toShape()) },
    ) { Text(title, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

/** The whole row toggles, not just the switch. */
@Composable
private fun SwitchItem(shapes: ListItemShapes, title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = shapes,
        enabled = enabled,
        supportingContent = { Text(if (enabled) summary else "Needs an OpenRouter key.") },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) { { Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(SwitchDefaults.IconSize)) } } else null,
            )
        },
    ) { Text(title) }
}

/** A slider with stops, one per value in [range], and the current value at the end of the title. */
@Composable
private fun SliderItem(shapes: ListItemShapes, title: String, value: Int, range: IntRange, label: (Int) -> String, note: String? = null, onChange: (Int) -> Unit) {
    SegmentedListItem(
        shapes = shapes,
        supportingContent = {
            Column {
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onChange(it.roundToInt()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = range.last - range.first - 1,
                )
                note?.let { Text(it) }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(label(value), style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Phone or OpenRouter voice, as a connected pair of toggle buttons (Expressive's take on segmented buttons). */
@Composable
private fun EngineItem(shapes: ListItemShapes, ui: UiSettings, hasKey: Boolean, openRouterVoice: Boolean) {
    SegmentedListItem(
        shapes = shapes,
        supportingContent = {
            Text(
                if (openRouterVoice) "Sounds more natural. Uses your OpenRouter credit." else "Android's built-in voice. Works offline.",
                Modifier.padding(top = 8.dp),
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            val engines = Settings.TtsEngine.entries
            engines.forEachIndexed { i, engine ->
                ToggleButton(
                    checked = ui.ttsEngine == engine && (hasKey || engine == Settings.TtsEngine.ANDROID),
                    onCheckedChange = { ui.ttsEngine = engine },
                    enabled = hasKey || engine == Settings.TtsEngine.ANDROID,
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        engines.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (engine == Settings.TtsEngine.ANDROID) "Phone" else "OpenRouter") }
            }
        }
    }
}

/** Shows the current model by name, with its ID and price. Tapping opens the search page. */
@Composable
private fun PickerItem(shapes: ListItemShapes, title: String, value: String, models: List<ModelInfo>?, enabled: Boolean = true, onClick: () -> Unit) {
    val model = models?.find { it.id == value }
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        supportingContent = {
            Column {
                Text(model?.name?.ifBlank { null } ?: value, color = MaterialTheme.colorScheme.onSurface)
                if (model != null) Text("${model.id} · ${model.price}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    ) { Text(title) }
}

@Composable
private fun PickerPage(picker: Picker, modifier: Modifier, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val matches = remember(q, picker.options) { picker.options.filter { q.isEmpty() || it.id.contains(q, true) || it.name.contains(q, true) } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = picker.options.indexOfFirst { it.id == picker.selected }.coerceAtLeast(0))
    LaunchedEffect(q) { if (q.isNotEmpty()) listState.scrollToItem(0) } // results start at the top, not where the full list was
    fun pick(id: String) {
        picker.onPick(id)
        onDone()
    }
    // Not listed (new, or the list didn't load): take what you typed as the ID. Only offered when nothing
    // matches or it looks like a full ID ("vendor/model"), so it doesn't sit on top of every search.
    val offerTyped = q.isNotEmpty() && matches.none { it.id == q } && (matches.isEmpty() || '/' in q)
    val extra = (if (picker.options.isEmpty()) 1 else 0) + (if (offerTyped) 1 else 0)
    val count = extra + matches.size
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(if (picker.options.isEmpty()) "Type an ID" else "Search") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SegmentedSurface {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                if (picker.options.isEmpty()) item {
                    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(0, count), supportingContent = { Text("Now: ${picker.selected}") }) { Text(picker.emptyHint) }
                }
                if (offerTyped) item {
                    SegmentedListItem(onClick = { pick(q) }, shapes = ListItemDefaults.segmentedShapes(extra - 1, count)) { Text("Use “$q”") }
                }
                itemsIndexed(matches, key = { _, option -> option.id }) { i, option ->
                    val selected = option.id == picker.selected
                    SegmentedListItem(
                        selected = selected,
                        onClick = { pick(option.id) },
                        shapes = ListItemDefaults.segmentedShapes(extra + i, count),
                        supportingContent = listOf(option.id.takeIf { it != option.name }, option.detail.ifBlank { null }).filterNotNull().takeIf { it.isNotEmpty() }
                            ?.let { parts -> { Text(parts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = if (selected) { { Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected") } } else null,
                    ) { Text(option.name.ifBlank { option.id }) }
                }
            }
        }
    }
}

/** Runs [run] and shows what it returned, or the error, in place of the description. For trying things without driving. */
@Composable
private fun TestItem(shapes: ListItemShapes, title: String, description: String, enabled: Boolean, run: suspend () -> String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = {
            busy = true
            result = null
            scope.launch {
                result = try { run() } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
                busy = false
            }
        },
        shapes = shapes,
        enabled = enabled && !busy,
        supportingContent = { Text(result ?: if (enabled) description else "Needs an OpenRouter key.") },
        trailingContent = if (busy) { { LoadingIndicator(Modifier.size(32.dp)) } } else null,
    ) { Text(title, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun KeyNeeded(onClick: () -> Unit) {
    ListGroup({
        SegmentedListItem(
            onClick = onClick,
            shapes = it,
            supportingContent = { Text("For AI tidy-up, the OpenRouter voice and speech to text.") },
            leadingContent = { ShapedIcon(R.drawable.ic_key) },
        ) { Text("Add an OpenRouter key", color = MaterialTheme.colorScheme.primary) }
    })
}

/** "Key works · $4.20 balance", or OpenRouter's reason for rejecting it. Re-checked when the key changes. */
@Composable
private fun rememberKeyStatus(settings: Settings, hasKey: Boolean, keyVersion: Int): KeyStatus {
    var status by remember { mutableStateOf(KeyStatus("Checking the key…", null)) }
    LaunchedEffect(hasKey, keyVersion) {
        if (!hasKey) return@LaunchedEffect
        status = KeyStatus("Checking the key…", null)
        val api = OpenRouter(settings.openRouterKey)
        status = try {
            val key = api.key()
            val balance = runCatching { api.balance() }.getOrNull()
            KeyStatus(
                listOfNotNull(
                    "Key works",
                    balance?.let { "$" + "%.2f".format(it) + " balance" },
                    key.limitRemaining?.let { "$" + "%.2f".format(it) + " left on this key's limit" },
                    "no credits bought yet".takeIf { key.freeTier },
                ).joinToString(" · "),
                true,
            )
        } catch (e: OpenRouterException) {
            KeyStatus("Key rejected: ${e.message}", false)
        } catch (e: Exception) {
            KeyStatus("Couldn't check the key. Are you online?", false)
        }
    }
    return status
}

/** The key row opens a dialog to paste it. The privacy notice shows once, the first time you save a key. */
@Composable
private fun KeyItem(shapes: ListItemShapes, settings: Settings, hasKey: Boolean, status: KeyStatus, onHasKey: (Boolean) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    fun save() {
        settings.openRouterKey = input.trim()
        settings.privacyNoticeSeen = true
        input = ""
        editing = false
        onHasKey(true)
    }

    SegmentedListItem(
        onClick = { editing = true },
        shapes = shapes,
        supportingContent = {
            Text(
                if (hasKey) status.text else "Not set. Tap to paste one.",
                color = when {
                    !hasKey || status.ok == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    status.ok -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        },
        leadingContent = {
            // Green-lit keys get the cheerful shape; missing or rejected ones a plain circle.
            ShapedIcon(R.drawable.ic_key, shape = (if (hasKey && status.ok == true) MaterialShapes.Cookie9Sided else MaterialShapes.Circle).toShape())
        },
    ) { Text("API key") }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false; input = "" },
            icon = { ShapedIcon(R.drawable.ic_key) },
            title = { Text(if (hasKey) "Replace key" else "OpenRouter key") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(enabled = input.isNotBlank(), onClick = { if (settings.privacyNoticeSeen) save() else askConsent = true }, shapes = ButtonDefaults.shapes()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false; input = "" }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        )
    }
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            icon = { ShapedIcon(R.drawable.ic_info) },
            title = { Text("Your messages go to OpenRouter") },
            text = {
                Text(
                    "With a key saved, the text of messages you play and your dictated voice replies are sent to OpenRouter " +
                        "and the AI providers it routes them to. Remove the key to stop that.",
                )
            },
            confirmButton = { Button(onClick = { askConsent = false; save() }, shapes = ButtonDefaults.shapes()) { Text("Save key") } },
            dismissButton = { TextButton(onClick = { askConsent = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RemoveKeyItem(shapes: ListItemShapes, settings: Settings, onHasKey: (Boolean) -> Unit) {
    var removing by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { removing = true },
        shapes = shapes,
        supportingContent = { Text("Turns off AI tidy-up, the OpenRouter voice and speech to text.") },
        leadingContent = { Spacer(Modifier.size(40.dp)) }, // lines up with the key row's icon
    ) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove the key?") },
            text = { Text("You'll need to paste it again to use the AI features.") },
            confirmButton = {
                Button(onClick = { settings.openRouterKey = null; removing = false; onHasKey(false) }, shapes = ButtonDefaults.shapes()) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
        )
    }
}

private const val LICENSE_URL = "https://github.com/MrFrederic/DriveGram/blob/main/LICENSE"

private const val CLEAN_UP_SAMPLE = "прив! скинь плз pdf до 5ти, ок? 👍 thx"
