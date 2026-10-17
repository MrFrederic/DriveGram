package app.drivegram

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tg = App.instance.telegram
        setContent {
            DriveGramTheme {
                val auth by tg.auth.collectAsStateWithLifecycle()
                val startupError by tg.startupError.collectAsStateWithLifecycle()
                when {
                    BuildConfig.TG_API_ID == 0 -> Centered("Add telegram.apiId and telegram.apiHash to local.properties, then rebuild.")
                    startupError != null -> Centered("TDLib didn't start: $startupError")
                    auth is TdApi.AuthorizationStateReady -> {
                        var showSettings by rememberSaveable { mutableStateOf(false) }
                        if (showSettings) {
                            BackHandler { showSettings = false }
                            SettingsScreen(App.instance.settings, onBack = { showSettings = false })
                        } else {
                            ChatListScreen(tg, onSettings = { showSettings = true })
                        }
                    }
                    else -> LoginScreen(tg, auth)
                }
            }
        }
    }
}

@Composable
fun DriveGramTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize(), content = content) }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (text.isEmpty()) CircularProgressIndicator() else Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** One input field per TDLib auth step: phone → code → 2FA password (plus email, which Telegram sometimes demands). */
private class AuthStep(
    val title: String,
    val label: String,
    val keyboard: KeyboardType,
    val hint: String? = null,
    val submit: (String) -> TdApi.Function<TdApi.Ok>,
)

private fun authStep(state: TdApi.AuthorizationState?): AuthStep? = when (state) {
    is TdApi.AuthorizationStateWaitPhoneNumber ->
        AuthStep("Log in to Telegram", "Phone number, with country code", KeyboardType.Phone) { TdApi.SetAuthenticationPhoneNumber(it, null) }
    is TdApi.AuthorizationStateWaitCode ->
        AuthStep("Enter the code", "Code", KeyboardType.Number, "Telegram sent it to ${state.codeInfo.phoneNumber}.") { TdApi.CheckAuthenticationCode(it) }
    is TdApi.AuthorizationStateWaitPassword ->
        AuthStep("Two-step verification", "Password", KeyboardType.Password, state.passwordHint.takeIf { it.isNotEmpty() }?.let { "Hint: $it" }) { TdApi.CheckAuthenticationPassword(it) }
    is TdApi.AuthorizationStateWaitEmailAddress ->
        AuthStep("Email required", "Email", KeyboardType.Email) { TdApi.SetAuthenticationEmailAddress(it) }
    is TdApi.AuthorizationStateWaitEmailCode ->
        AuthStep("Check your email", "Email code", KeyboardType.Number, "Sent to ${state.codeInfo.emailAddressPattern}.") {
            TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(it))
        }
    else -> null
}

@Composable
private fun LoginScreen(tg: Telegram, state: TdApi.AuthorizationState?) {
    val step = authStep(state)
    if (step == null) {
        val message = when (state) {
            null, is TdApi.AuthorizationStateWaitTdlibParameters -> ""
            is TdApi.AuthorizationStateWaitRegistration -> "This number has no Telegram account. Sign up in the official app first."
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> "Logging out…"
            is TdApi.AuthorizationStateClosed -> "Telegram session closed. Restart the app."
            else -> "Finish logging in with the official Telegram app, then come back."
        }
        Centered(message)
        return
    }
    val scope = rememberCoroutineScope()
    // Keyed on the step so the field clears when TDLib moves to the next one.
    var input by rememberSaveable(step.title) { mutableStateOf("") }
    var error by remember(step.title) { mutableStateOf<String?>(null) }
    var busy by remember(step.title) { mutableStateOf(false) }
    val hidden = step.keyboard == KeyboardType.Password

    Column(
        Modifier.fillMaxSize().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(step.title, style = MaterialTheme.typography.headlineMedium)
        step.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            label = { Text(step.label) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = step.keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy,
            onClick = {
                if (input.isBlank()) { error = "Enter a value first"; return@Button }
                busy = true
                scope.launch {
                    try {
                        tg.send(step.submit(input.trim()))
                    } catch (e: TdException) {
                        error = e.error.message
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

/** Immutable snapshot for the UI: TdApi.Chat is mutated in place, so Compose can't see changes on it. */
data class ChatRow(val id: Long, val title: String, val unread: Int, val preview: String)

fun TdApi.Chat.toRow() = ChatRow(id, title, unreadCount, preview(lastMessage))

/** The same words Play would say, minus the sender. Service messages preview as empty. */
fun preview(message: TdApi.Message?): String =
    message?.let { spokenMessages(listOf(it), isGroup = false, names = emptyMap(), defaultLang = phoneLang()).single().text() }.orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(tg: Telegram, onSettings: () -> Unit) {
    val folders by tg.folders.collectAsStateWithLifecycle()
    val version by tg.chatsVersion.collectAsStateWithLifecycle()
    val reader = App.instance.reader
    val playingChat by reader.playingChat.collectAsStateWithLifecycle()
    val playLoading by reader.loading.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) } // 0 = all chats, n = folders[n - 1]
    val list: TdApi.ChatList = folders.getOrNull(selected - 1)?.let { TdApi.ChatListFolder(it.id) } ?: TdApi.ChatListMain()
    var loadError by remember(selected) { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf<ChatRow?>(null) }
    // Ask for the mic and notifications here, on the phone: the car can't reliably show the prompt (Android 17 blocks it).
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
    replyTo?.let { ReplyDialog(it, onDone = { replyTo = null }) }

    LaunchedEffect(selected, folders.size) {
        try { tg.load(list, 50) } catch (e: TdException) { loadError = e.error.message }
    }
    val rows = remember(version, selected, folders) { tg.chatsIn(list, 50).map { it.toRow() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DriveGram") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (folders.isNotEmpty()) {
                PrimaryScrollableTabRow(selectedTabIndex = selected.coerceAtMost(folders.size)) {
                    Tab(selected == 0, onClick = { selected = 0 }, text = { Text("All chats") })
                    folders.forEachIndexed { i, folder ->
                        Tab(selected == i + 1, onClick = { selected = i + 1 }, text = { Text(folder.title) })
                    }
                }
            }
            loadError?.let { Text("Couldn't load chats: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn {
                items(rows, key = { it.id }) { row ->
                    ListItem(
                        // Unread chats stand out by weight as well as the badge, like Messages and Gmail.
                        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (row.unread > 0) FontWeight.SemiBold else null) },
                        supportingContent = { Text(row.preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (row.unread > 0) Badge { Text("${row.unread}") }
                                IconButton(onClick = { reader.toggle(row.id) }) {
                                    val playing = playingChat == row.id
                                    // Still tappable while loading: it stops, like the Stop icon would.
                                    if (playing && playLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else Icon(painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play), contentDescription = if (playing) "Stop" else "Play")
                                }
                                IconButton(onClick = { replyTo = row }) { Icon(painterResource(R.drawable.ic_mic), contentDescription = "Reply") }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The phone's version of the car reply flow, for checking it works (and testing on an emulator). */
@Composable
private fun ReplyDialog(chat: ChatRow, onDone: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<ReplySession?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) session = App.instance.replies.start(chat.id, PhoneMic()) else onDone()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            session = App.instance.replies.start(chat.id, PhoneMic())
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val current = session ?: return
    val state by current.state.collectAsStateWithLifecycle()
    val secondsLeft by current.secondsLeft.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == ReplyState.Cancelled) onDone()
        if (state == ReplyState.Sent) { delay(1200); onDone() }
    }
    DisposableEffect(current) { onDispose { if (current.state.value != ReplyState.Sent) current.cancel() } }

    val (text, primary) = when (val s = state) {
        ReplyState.Recording -> "Listening… $secondsLeft s left" to ("Done" to current::stopRecording)
        ReplyState.Working -> "Getting your message…" to null
        ReplyState.Sending -> "Sending…" to null
        is ReplyState.Confirm -> s.text to ("Send" to current::send)
        ReplyState.NothingHeard -> "Didn't catch anything." to ("Try again" to current::retry)
        ReplyState.VoiceOnly -> "Couldn't turn that into text. Send it as a voice message?" to ("Send voice" to current::send)
        is ReplyState.SendFailed -> "Couldn't send it." to ("Try again" to current::send)
        ReplyState.Sent -> "Sent" to null
        ReplyState.Cancelled -> "Cancelled" to null
    }
    AlertDialog(
        onDismissRequest = { current.cancel() },
        title = { Text("Reply to ${chat.title}") },
        text = { Text(text) },
        confirmButton = { primary?.let { (label, action) -> TextButton(onClick = action) { Text(label) } } },
        dismissButton = { if (state != ReplyState.Sent) TextButton(onClick = { current.cancel() }) { Text("Cancel") } },
    )
}
