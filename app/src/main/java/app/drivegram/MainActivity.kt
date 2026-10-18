@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.drivegram

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
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
                val needsApiKeys by tg.needsApiKeys.collectAsStateWithLifecycle()
                when {
                    needsApiKeys -> ApiKeysScreen(onSave = tg::useApiKeys)
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
    var keysRejected by remember(step.title) { mutableStateOf(false) }
    val hidden = step.keyboard == KeyboardType.Password

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        ShapedIcon(R.drawable.ic_chats, shape = MaterialShapes.SoftBurst.toShape(), size = 88.dp)
        Text(step.title, style = MaterialTheme.typography.headlineLargeEmphasized)
        step.hint?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            label = { Text(step.label) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = step.keyboard),
            shape = MaterialTheme.shapes.large,
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
                        keysRejected = "API_ID" in e.error.message
                        error = if (keysRejected) "Telegram didn't accept these API keys." else e.error.message
                    } finally {
                        busy = false
                    }
                }
            },
            shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        ) {
            if (busy) LoadingIndicator(Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight) * 1.5f), color = LocalContentColor.current)
            else Text("Continue", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
        }
        if (keysRejected) WrongApiKeys(onReset = tg::resetApiKeys)
    }
}

/**
 * Immutable snapshot for the UI: TdApi.Chat is mutated in place, so Compose can't see changes on it.
 * [photoId] is the small photo's file id, so a new chat photo counts as a change.
 */
data class ChatRow(val id: Long, val title: String, val unread: Int, val preview: String, val photoId: Int? = null)

fun TdApi.Chat.toRow() = ChatRow(id, title, unreadCount, preview(lastMessage), photo?.small?.id)

/** The same words Play would say, minus the sender. Service messages preview as empty. */
fun preview(message: TdApi.Message?): String =
    message?.let { spokenMessages(listOf(it), isGroup = false, names = emptyMap(), defaultLang = phoneLang()).single().text() }.orEmpty()

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
    val unreadChats = rows.count { it.unread > 0 }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("DriveGram") },
                subtitle = { Text(if (unreadChats == 0) "All caught up" else "$unreadChats unread ${if (unreadChats == 1) "chat" else "chats"}") },
                actions = {
                    IconButton(onClick = onSettings, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            if (folders.isNotEmpty()) {
                val titles = listOf("All chats") + folders.map { it.title }
                FolderRow(titles, selected.coerceAtMost(folders.size)) { selected = it }
            }
            loadError?.let { Text("Couldn't load chats: $it", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            SegmentedSurface {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { i, row ->
                        ChatItem(
                            row,
                            index = i,
                            count = rows.size,
                            photo = rememberChatPhoto(tg, row),
                            playing = playingChat == row.id,
                            loading = playingChat == row.id && playLoading,
                            onPlay = { reader.toggle(row.id) },
                            onReply = { replyTo = row },
                            // Chats move when a message arrives: let them slide into place instead of jumping.
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/** Folders as a connected button group: the selected one turns round, the rest stay squarish. */
@Composable
private fun FolderRow(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        itemsIndexed(titles) { i, title ->
            ToggleButton(
                checked = i == selected,
                onCheckedChange = { onSelect(i) },
                // Unselected buttons default to surfaceContainer, the page colour itself: one step up so they show.
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    titles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(title, maxLines = 1) }
        }
    }
}

@Composable
private fun ChatItem(
    row: ChatRow,
    index: Int,
    count: Int,
    photo: ImageBitmap?,
    playing: Boolean,
    loading: Boolean,
    onPlay: () -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier,
        leadingContent = { ChatAvatar(row, photo) },
        supportingContent = { Text(row.preview, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // A toggle: it morphs from round to squarish while this chat is being read out.
                FilledTonalIconToggleButton(checked = playing, onCheckedChange = { onPlay() }, shapes = IconButtonDefaults.toggleableShapes()) {
                    // Still tappable while loading: it stops, like the Stop icon would.
                    if (loading) LoadingIndicator(Modifier.size(28.dp), color = LocalContentColor.current)
                    else Icon(painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play), contentDescription = if (playing) "Stop" else "Play")
                }
                FilledTonalIconButton(onClick = onReply, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = "Reply")
                }
            }
        },
    ) {
        // Unread chats stand out by weight as well as the badge, like Messages and Gmail.
        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (row.unread > 0) FontWeight.SemiBold else null)
    }
}

/**
 * The chat's Telegram photo once it's downloaded, null until then (or if it has none). Shares [Avatars]' cache and
 * size with the car and notifications, so a photo fetched for one is there for the others.
 */
@Composable
private fun rememberChatPhoto(tg: Telegram, row: ChatRow): ImageBitmap? {
    val chat = tg.chat(row.id)
    var photo by remember(row.id, row.photoId) {
        mutableStateOf(chat?.takeIf { row.photoId != null && !Avatars.missing(it, AVATAR_PX) }?.let { Avatars.now(it, AVATAR_PX).asImageBitmap() })
    }
    LaunchedEffect(row.id, row.photoId) {
        if (photo == null && chat != null && row.photoId != null && Avatars.load(tg, chat, AVATAR_PX)) photo = Avatars.now(chat, AVATAR_PX).asImageBitmap()
    }
    return photo
}

private const val AVATAR_PX = 128

/** The chat photo, else its first letter, on a shape: a cookie for unread chats, a plain circle for read ones. */
@Composable
private fun ChatAvatar(row: ChatRow, photo: ImageBitmap?) {
    val unread = row.unread > 0
    val colors = MaterialTheme.colorScheme
    // The end padding keeps a wide badge ("12") off the chat title.
    BadgedBox(badge = { if (unread) Badge { Text("${row.unread}") } }, Modifier.padding(end = 6.dp)) {
        Box(
            Modifier.size(44.dp)
                .clip(if (unread) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Circle.toShape())
                // No fill behind a photo: Avatars' photos are already round, and a fill would peek out at the cookie's tips.
                .background(if (photo != null) Color.Transparent else if (unread) colors.primaryContainer else colors.surfaceVariant)
                // The photo is drawn over the letter rather than swapped in as an Image: adding a layout node when the
                // photo arrives crashed Compose while LazyColumn was prefetching the row ("LayoutNode not found in RectList").
                .drawWithContent {
                    if (photo == null) drawContent()
                    else drawImage(photo, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.High)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = if (unread) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
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
    val waiting = state == ReplyState.Working || state == ReplyState.Sending
    AlertDialog(
        onDismissRequest = { current.cancel() },
        icon = {
            // The mic sits on a burst while it listens, a calm circle otherwise; the indicator takes over while waiting.
            if (waiting) LoadingIndicator(Modifier.size(48.dp))
            else ShapedIcon(
                if (state == ReplyState.Sent) R.drawable.ic_check else R.drawable.ic_mic,
                shape = if (state == ReplyState.Recording) MaterialShapes.SoftBurst.toShape() else MaterialShapes.Circle.toShape(),
                size = 56.dp,
            )
        },
        title = { Text("Reply to ${chat.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { primary?.let { (label, action) -> Button(onClick = action, shapes = ButtonDefaults.shapes()) { Text(label) } } },
        dismissButton = { if (state != ReplyState.Sent) TextButton(onClick = { current.cancel() }, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
    )
}
