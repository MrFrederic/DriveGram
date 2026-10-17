package app.drivegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Laid out after Google's Android settings guidelines (source.android.com/docs/core/settings/settings-guidelines):
// a short home list that shows each area's current values, one page per area, single choices in a dialog rather
// than radio buttons in the list, and the long model lists on their own searchable page.

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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(picker?.title ?: page?.title ?: "Settings") },
                navigationIcon = { IconButton(onClick = ::back) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") } },
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
        Column(modifier.verticalScroll(rememberScrollState())) {
            when (page) {
                null -> {
                    NavItem(R.drawable.ic_play, "Reading aloud", readingSummary(ui, hasKey)) { page = Page.READING }
                    NavItem(R.drawable.ic_mic, "Replying", replyingSummary(ui, hasKey)) { page = Page.REPLYING }
                    NavItem(R.drawable.ic_key, "OpenRouter", if (hasKey) keyStatus.text else "No key: AI tidy-up, the OpenRouter voice and speech to text are off") {
                        page = Page.OPENROUTER
                    }
                    val uriHandler = LocalUriHandler.current
                    NavItem(R.drawable.ic_info, "Licence", "PolyForm Noncommercial 1.0.0 · free to use, modify and share, no commercial use") {
                        uriHandler.openUri(LICENSE_URL)
                    }
                }

                Page.READING -> {
                    SliderItem("Messages to read when nothing is unread", ui.lastN, 1..10, { "$it" }) { ui.lastN = it }
                    SwitchItem(
                        "Tidy up text with AI", "Fixes typos and expands abbreviations so the voice sounds natural. Never changes the meaning.",
                        checked = ui.readAloudLlm && hasKey, enabled = hasKey,
                    ) { ui.readAloudLlm = it }
                    SectionTitle("Voice")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Settings.TtsEngine.entries.forEachIndexed { i, engine ->
                            SegmentedButton(
                                selected = ui.ttsEngine == engine && (hasKey || engine == Settings.TtsEngine.ANDROID),
                                onClick = { ui.ttsEngine = engine },
                                enabled = hasKey || engine == Settings.TtsEngine.ANDROID,
                                shape = SegmentedButtonDefaults.itemShape(i, Settings.TtsEngine.entries.size),
                            ) { Text(if (engine == Settings.TtsEngine.ANDROID) "Phone" else "OpenRouter") }
                        }
                    }
                    val openRouterVoice = ui.ttsEngine == Settings.TtsEngine.OPENROUTER && hasKey
                    Text(
                        if (openRouterVoice) "Sounds more natural. Uses your OpenRouter credit." else "Android's built-in voice. Works offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (openRouterVoice) {
                        PickerItem("Voice model", ui.ttsModel, speechModels) {
                            modelPicker("Voice model", ui.ttsModel, speechModels) { picked ->
                                ui.ttsModel = picked
                                fixVoice(ui, speechModels)
                            }
                        }
                        val voices = speechModels?.find { it.id == ui.ttsModel }?.voices.orEmpty()
                        PickerItem("Voice", ui.ttsVoice, null) {
                            picker = Picker("Voice", ui.ttsVoice, voices.map { Option(it, it, "") }, "This model doesn't list its voices. Type a voice name.") {
                                ui.ttsVoice = it
                            }
                        }
                    }
                    TestItem("Test voice", "Plays a sample sentence with this voice.", enabled = true) {
                        val lang = phoneLang()
                        App.instance.reader.voice()
                            .prepare(if (lang == "ru") "Так DriveGram будет читать ваши сообщения." else "This is how DriveGram reads your messages.", lang)
                            .play()
                        "Played"
                    }
                    if (!hasKey) KeyNeeded { page = Page.OPENROUTER }
                }

                Page.REPLYING -> {
                    SwitchItem(
                        "Tidy up dictation with AI", "Removes filler words and false starts. Keeps your words, style and language.",
                        checked = ui.cleanUpDictation && hasKey, enabled = hasKey,
                    ) { ui.cleanUpDictation = it }
                    SliderItem("Stop listening after silence", ui.silenceSeconds, 0..15, { if (it == 0) "Off" else "$it s" }) { ui.silenceSeconds = it }
                    Text(
                        "${if (ui.silenceSeconds == 0) "Only Done stops the recording." else "Done also stops it."} Recordings end after ${MAX_RECORDING_MS / 1000} s.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SwitchItem(
                        "Send without asking", "Skips Send / Cancel. The AI may have reworded what you said, so this is off unless you turn it on.",
                        checked = ui.autoSend, enabled = true,
                    ) { ui.autoSend = it }
                    if (hasKey) {
                        PickerItem("Speech-to-text model", ui.sttModel, transcriptionModels) {
                            modelPicker("Speech-to-text model", ui.sttModel, transcriptionModels) { ui.sttModel = it }
                        }
                    }
                    val context = LocalContext.current
                    TestItem("Test speech to text", "The phone voice says a sample sentence and the model writes it down.", enabled = hasKey) {
                        val file = java.io.File(context.cacheDir, "stt-test.wav")
                        App.instance.reader.phoneVoice.synthesizeToFile("Привет! Буду через десять минут, see you soon.", "ru", file)
                        "Heard: " + OpenRouterSpeechToText(OpenRouter(settings.openRouterKey), settings.sttModel).transcribe(file.readBytes())
                    }
                    if (!hasKey) KeyNeeded { page = Page.OPENROUTER }
                }

                Page.OPENROUTER -> {
                    KeyItem(settings, hasKey, keyStatus) { hasKey = it; keyVersion++ }
                    PickerItem("Tidy-up model", ui.llmModel, textModels, enabled = hasKey) {
                        modelPicker("Tidy-up model", ui.llmModel, textModels) { ui.llmModel = it }
                    }
                    TestItem("Test tidy-up", "Tidies up a sample message the way Play would.", enabled = hasKey) {
                        try {
                            OpenRouterRewriter(OpenRouter(settings.openRouterKey), settings.llmModel, cached = false).prepareForSpeech(CLEAN_UP_SAMPLE, "ru")
                        } catch (e: RewriteRejected) {
                            "${e.message}:\n${e.output}"
                        }
                    }
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
private fun NavItem(icon: Int, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Footer(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** The whole row toggles, not just the switch. */
@Composable
private fun SwitchItem(title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (enabled) summary else "Needs an OpenRouter key.") },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** A slider with stops, one per value in [range], and the current value at the end of the title. */
@Composable
private fun SliderItem(title: String, value: Int, range: IntRange, label: (Int) -> String, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                Text(label(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
            )
        },
    )
}

/** Shows the current model by name, with its ID and price. Tapping opens the search page. */
@Composable
private fun PickerItem(title: String, value: String, models: List<ModelInfo>?, enabled: Boolean = true, onClick: () -> Unit) {
    val model = models?.find { it.id == value }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(model?.name?.ifBlank { null } ?: value, color = MaterialTheme.colorScheme.onSurface)
                if (model != null) Text("${model.id} · ${model.price}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
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
        LazyColumn(state = listState) {
            if (picker.options.isEmpty()) item { ListItem(headlineContent = { Text(picker.emptyHint) }, supportingContent = { Text("Now: ${picker.selected}") }) }
            // Not listed (new, or the list didn't load): take what you typed as the ID. Only offered when nothing
            // matches or it looks like a full ID ("vendor/model"), so it doesn't sit on top of every search.
            if (q.isNotEmpty() && matches.none { it.id == q } && (matches.isEmpty() || '/' in q)) {
                item { ListItem(headlineContent = { Text("Use “$q”") }, modifier = Modifier.clickable { pick(q) }) }
            }
            items(matches, key = { it.id }) { option ->
                val selected = option.id == picker.selected
                ListItem(
                    headlineContent = { Text(option.name.ifBlank { option.id }, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = listOf(option.id.takeIf { it != option.name }, option.detail.ifBlank { null }).filterNotNull().takeIf { it.isNotEmpty() }
                        ?.let { parts -> { Text(parts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = if (selected) { { Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) } } else null,
                    modifier = Modifier.clickable { pick(option.id) },
                )
            }
        }
    }
}

/** Runs [run] and shows what it returned, or the error, in place of the description. For trying things without driving. */
@Composable
private fun TestItem(title: String, description: String, enabled: Boolean, run: suspend () -> String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        supportingContent = { Text(result ?: if (enabled) description else "Needs an OpenRouter key.") },
        trailingContent = if (busy) { { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } } else null,
        modifier = Modifier.clickable(enabled = enabled && !busy) {
            busy = true
            result = null
            scope.launch {
                result = try { run() } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
                busy = false
            }
        },
    )
}

@Composable
private fun KeyNeeded(onClick: () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    ListItem(
        headlineContent = { Text("Add an OpenRouter key", color = MaterialTheme.colorScheme.primary) },
        supportingContent = { Text("For AI tidy-up, the OpenRouter voice and speech to text.") },
        leadingContent = { Icon(painterResource(R.drawable.ic_key), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
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
private fun KeyItem(settings: Settings, hasKey: Boolean, status: KeyStatus, onHasKey: (Boolean) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    fun save() {
        settings.openRouterKey = input.trim()
        settings.privacyNoticeSeen = true
        input = ""
        editing = false
        onHasKey(true)
    }

    ListItem(
        headlineContent = { Text("API key") },
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
        leadingContent = { Icon(painterResource(R.drawable.ic_key), contentDescription = null) },
        modifier = Modifier.clickable { editing = true },
    )
    if (hasKey) {
        ListItem(
            headlineContent = { Text("Remove key") },
            supportingContent = { Text("Turns off AI tidy-up, the OpenRouter voice and speech to text.") },
            modifier = Modifier.clickable { removing = true },
        )
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false; input = "" },
            title = { Text(if (hasKey) "Replace key" else "OpenRouter key") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(enabled = input.isNotBlank(), onClick = { if (settings.privacyNoticeSeen) save() else askConsent = true }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false; input = "" }) { Text("Cancel") } },
        )
    }
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("Your messages go to OpenRouter") },
            text = {
                Text(
                    "With a key saved, the text of messages you play and your dictated voice replies are sent to OpenRouter " +
                        "and the AI providers it routes them to. Remove the key to stop that.",
                )
            },
            confirmButton = { TextButton(onClick = { askConsent = false; save() }) { Text("Save key") } },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Cancel") } },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove the key?") },
            text = { Text("You'll need to paste it again to use the AI features.") },
            confirmButton = { TextButton(onClick = { settings.openRouterKey = null; removing = false; onHasKey(false) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

private const val LICENSE_URL = "https://github.com/MrFrederic/DriveGram/blob/main/LICENSE"

private const val CLEAN_UP_SAMPLE = "прив! скинь плз pdf до 5ти, ок? 👍 thx"
