package app.drivegram.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.drivegram.App
import app.drivegram.Avatars
import app.drivegram.BuildConfig
import app.drivegram.ChatSource
import app.drivegram.MicSource
import app.drivegram.Notifier
import app.drivegram.Playback
import app.drivegram.R
import app.drivegram.ReplySession
import app.drivegram.ReplyState
import app.drivegram.TdException
import app.drivegram.Telegram
import app.drivegram.carTitle
import app.drivegram.title
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.DateFormat

/** Row avatars in pixels: sharp at the host's large row image (about the size of the Play button). */
private const val AVATAR_PX = 128

class DriveGramCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // the DHU and debug hosts aren't on the allowlist
        } else {
            HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            val root = RootScreen(carContext, App.instance.telegram, App.instance.reader)
            val reply = fromNotification(intent) ?: return root
            carContext.getCarService(ScreenManager::class.java).push(root) // Back from the reply lands on the chat list
            return reply
        }

        override fun onNewIntent(intent: Intent) {
            fromNotification(intent)?.let { carContext.getCarService(ScreenManager::class.java).push(it) }
        }

        /** A notification's Play (starts reading, stays on the current screen) or Reply (returns the reply screen). */
        private fun fromNotification(intent: Intent): Screen? {
            val chatId = intent.getLongExtra(Notifier.EXTRA_CHAT, 0)
            return when (intent.action) {
                Notifier.CAR_PLAY -> null.also { App.instance.reader.play(chatId) }
                Notifier.CAR_REPLY -> App.instance.telegram.chat(chatId)?.let { replyScreen(carContext, chatId, carTitle(it)) }
                else -> null
            }
        }
    }
}

/**
 * Re-renders when Telegram data changes (at most once a second so the host doesn't throttle us) and right away when
 * playback starts or stops. [tg] and [playback] are parameters so the car screen tests can pass fakes.
 */
@OptIn(FlowPreview::class)
abstract class TelegramScreen(
    carContext: CarContext,
    protected val tg: ChatSource,
    protected val playback: Playback,
) : Screen(carContext) {
    private val loading = mutableSetOf<String>()
    private val photoLoads = mutableSetOf<Int>()
    /** Bumped as chat photos arrive; goes through the same once-a-second refresh as Telegram updates. */
    private val photosLoaded = MutableStateFlow(0)
    private val loaded = mutableSetOf<String>()

    protected val listLimit: Int
        get() = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                merge(tg.auth, tg.folders, tg.chatsVersion, photosLoaded).sample(1000).collect { invalidate() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { merge(playback.playingChat, playback.loading).collect { invalidate() } }
        }
    }

    protected fun icon(res: Int): CarIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    /**
     * Chat rows for [list] (only those passing [filter]), loading the chats from TDLib the first time the list is
     * shown.
     */
    protected fun chatList(list: TdApi.ChatList, noItems: String = "No chats here", filter: (TdApi.Chat) -> Boolean = { true }): ItemList? {
        val key = Telegram.key(list)
        val limit = listLimit
        if (key !in loaded && loading.add(key)) {
            lifecycleScope.launch {
                try {
                    tg.load(list, limit)
                } catch (e: TdException) {
                    CarToast.makeText(carContext, "Couldn't load chats", CarToast.LENGTH_LONG).show()
                } finally {
                    loading -= key
                    loaded += key
                    invalidate()
                }
            }
        }
        val chats = tg.chatsIn(list, limit).filter(filter)
        if (chats.isEmpty() && key in loading) return null // caller shows the loading spinner
        return ItemList.Builder()
            .setNoItemsMessage(noItems)
            .apply { chats.forEach { addItem(chatRow(it)) } }
            .build()
    }

    /**
     * Android Auto keeps only one action per row in practice (tested on AA 17.8, which drops the second one and
     * logs "Exceeded the maximum number of actions"), and a badge can't sit next to a single action.
     * So: Play on the row, unread count in the text, tap the row to reply.
     */
    private fun chatRow(chat: TdApi.Chat): Row {
        val time = chat.lastMessage?.let { lastActivity(it.date * 1000L) } ?: ""
        return Row.Builder()
            .setTitle(carTitle(chat))
            .apply { avatar(chat)?.let { setImage(it, Row.IMAGE_TYPE_LARGE) } }
            .addText(
                when {
                    isPlaying(chat.id) && playback.loading.value -> "Loading messages…" // a row action can't show a spinner
                    chat.unreadCount > 0 -> unreadText(chat, time)
                    else -> time
                },
            )
            .addAction(playAction(chat.id))
            .setOnClickListener { startReply(chat.id, carTitle(chat)) }
            .build()
    }

    /**
     * "● 3 unread · 2:49 AM" with the count in colour. Rows have no badge slot, and a row image would push the titles
     * of read chats out of line. Muted chats stay grey, as in Telegram.
     */
    private fun unreadText(chat: TdApi.Chat, time: CharSequence): CharSequence {
        val unread = "● ${chat.unreadCount} unread"
        val text = SpannableString("$unread · $time")
        // ponytail: chats on the default mute setting count as unmuted; reading the scope defaults would fix channels.
        val muted = chat.notificationSettings?.let { !it.useDefaultMuteFor && it.muteFor > 0 } == true
        val color = if (muted) CarColor.DEFAULT else unreadColor
        text.setSpan(ForegroundCarColorSpan.create(color), 0, unread.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    /**
     * Your phone's Material You primary colour (Android 12+), Telegram blue before that. Light and dark variants:
     * the host picks one for day or night and falls back to its default if the contrast is too low.
     */
    private val unreadColor: CarColor =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            CarColor.createCustom(carContext.getColor(android.R.color.system_accent1_600), carContext.getColor(android.R.color.system_accent1_200))
        } else {
            CarColor.BLUE
        }

    /** The chat's photo, its initials until the photo is downloaded. The host reads it by content URI. Null if that fails. */
    private fun avatar(chat: TdApi.Chat): CarIcon? {
        val file = chat.photo?.small
        if (file != null && Avatars.missing(chat, AVATAR_PX, carContext) && photoLoads.add(file.id)) {
            lifecycleScope.launch { if (Avatars.load(tg, chat, AVATAR_PX, carContext)) photosLoaded.value++ }
        }
        val uri = runCatching { Avatars.uri(carContext, chat, AVATAR_PX) }.getOrNull() ?: return null // a row without a picture still works
        carContext.hostInfo?.packageName?.let { carContext.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return CarIcon.Builder(IconCompat.createWithContentUri(uri)).build()
    }

    /** "1:11 AM" today, otherwise "Yesterday", "2 days ago", "Oct 10". */
    private fun lastActivity(millis: Long): CharSequence =
        if (DateUtils.isToday(millis)) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(millis)
        } else {
            DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)
        }

    protected fun isPlaying(chatId: Long) = playback.playingChat.value == chatId

    /** Play, or Stop while this chat is being read. */
    protected fun playAction(chatId: Long): Action = Action.Builder()
        .setIcon(icon(if (isPlaying(chatId)) R.drawable.ic_stop else R.drawable.ic_play))
        .setOnClickListener { playback.toggle(chatId) }
        .build()

    /** The car mic needs RECORD_AUDIO; the first time, Android Auto asks you to allow it on the phone. */
    private fun startReply(chatId: Long, title: String) {
        replyScreen(carContext, chatId, title, toast = false)?.let {
            screenManager.push(it)
            return
        }
        // Android 17 blocks the phone-side prompt this opens ("Background activity launch blocked"), so say what
        // to do first; the phone app also asks for the mic when it opens.
        CarToast.makeText(carContext, "Allow the microphone for DriveGram on your phone", CarToast.LENGTH_LONG).show()
        carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
            if (Manifest.permission.RECORD_AUDIO in granted) replyScreen(carContext, chatId, title)?.let { screenManager.push(it) }
        }
    }

    protected fun listTemplate(items: ItemList?, header: Header): ListTemplate =
        ListTemplate.Builder()
            .setHeader(header)
            .apply { if (items == null) setLoading(true) else setSingleList(items) }
            .build()

    protected fun backHeader(title: String) = Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build()
}

/**
 * The first screen: chats with messages since the car connected, newest first, and a Folders button for the rest.
 * No TabTemplate: Android Auto treats any change inside a tab as a new screen and scrolls it back to the top, so
 * Play/Stop, "Loading…" and every new message would throw you up the list (tested on AA 17.8). Plain lists keep
 * their scroll position.
 */
/** A reply screen for [chatId], or null (with a toast saying why) if the mic isn't allowed yet. */
fun replyScreen(carContext: CarContext, chatId: Long, title: String, toast: Boolean = true): Screen? {
    if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
        return ReplyScreen(carContext, title, App.instance.replies.start(chatId, CarMic(carContext)))
    }
    if (toast) CarToast.makeText(carContext, "Allow the microphone for DriveGram on your phone", CarToast.LENGTH_LONG).show()
    return null
}

class RootScreen(carContext: CarContext, tg: ChatSource, playback: Playback) : TelegramScreen(carContext, tg, playback) {
    /** Telegram dates are in seconds. A new car session is a new drive. */
    private val driveStarted = System.currentTimeMillis() / 1000

    override fun onGetTemplate(): Template {
        if (tg.auth.value !is TdApi.AuthorizationStateReady) {
            return MessageTemplate.Builder("Open DriveGram on your phone and log in to Telegram.")
                .setHeader(Header.Builder().setTitle("DriveGram").setStartHeaderAction(Action.APP_ICON).build())
                .build()
        }
        val folders = Action.Builder()
            .setIcon(icon(R.drawable.ic_folder))
            .setOnClickListener { screenManager.push(FoldersScreen(carContext, tg, playback)) }
            .build()
        return listTemplate(
            chatList(TdApi.ChatListMain(), "No new messages during this drive") {
                (it.lastMessage?.date ?: 0) >= driveStarted
            },
            Header.Builder().setTitle("This drive").setStartHeaderAction(Action.APP_ICON).addEndHeaderAction(folders).build(),
        )
    }
}

/** All chats, then your Telegram folders, each with how many of its chats are unread. */
class FoldersScreen(carContext: CarContext, tg: ChatSource, playback: Playback) : TelegramScreen(carContext, tg, playback) {
    override fun onGetTemplate(): Template {
        val lists = listOf(Triple<TdApi.ChatList, String, Int>(TdApi.ChatListMain(), "All chats", R.drawable.ic_chats)) +
            tg.folders.value.map { Triple(TdApi.ChatListFolder(it.id), it.title, R.drawable.ic_folder) }
        val items = ItemList.Builder()
        lists.take(listLimit).forEach { (list, title, iconRes) ->
            val unread = tg.unreadChats(list)
            items.addItem(
                Row.Builder()
                    .setTitle(title)
                    .setImage(icon(iconRes))
                    .apply { if (unread > 0) setNumericDecoration(unread) }
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(ChatsScreen(carContext, list, title, tg, playback)) }
                    .build(),
            )
        }
        return listTemplate(items.build(), backHeader("Folders"))
    }
}

class ChatsScreen(carContext: CarContext, private val list: TdApi.ChatList, private val name: String, tg: ChatSource, playback: Playback) :
    TelegramScreen(carContext, tg, playback) {
    override fun onGetTemplate(): Template = listTemplate(chatList(list), backHeader(name))
}

@SuppressLint("MissingPermission") // checked in startReply
class CarMic(carContext: CarContext) : MicSource {
    private val record = CarAudioRecord.create(carContext)
    override fun start() = record.startRecording()
    override fun read(buffer: ByteArray, offset: Int, size: Int) = record.read(buffer, offset, size)
    override fun stop() = record.stopRecording()
}

/**
 * One reply, step by step. Every state uses a MessageTemplate with the same header, so the host can treat the
 * updates as refreshes of one screen instead of new steps (Android Auto caps a task at 5 steps).
 */
class ReplyScreen(carContext: CarContext, private val title: String, private val session: ReplySession) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { state ->
                    when (state) {
                        ReplyState.Cancelled -> screenManager.pop()
                        ReplyState.Sent -> {
                            invalidate()
                            delay(1500)
                            screenManager.pop()
                        }
                        else -> invalidate()
                    }
                }
            }
        }
        lifecycleScope.launch {
            // Same template, new text: a refresh, not a new step.
            repeatOnLifecycle(Lifecycle.State.STARTED) { session.secondsLeft.collect { if (session.state.value == ReplyState.Recording) invalidate() } }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Leaving with Back (or the host closing us) mid-way cancels: nothing is sent behind your back.
            override fun onDestroy(owner: LifecycleOwner) {
                if (session.state.value != ReplyState.Sent) session.cancel()
            }
        })
    }

    private fun action(label: String, primary: Boolean = false, onClick: () -> Unit) = Action.Builder()
        .setTitle(label)
        .apply { if (primary) setFlags(Action.FLAG_PRIMARY) }
        .setOnClickListener(onClick)
        .build()

    override fun onGetTemplate(): Template {
        val header = Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build()
        fun message(text: String) = MessageTemplate.Builder(text).setHeader(header)
        val cancel = action("Cancel") { session.cancel() }
        return when (val state = session.state.value) {
            ReplyState.Recording -> message("Listening… ${session.secondsLeft.value} s left")
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build())
                .addAction(action("Done", primary = true) { session.stopRecording() })
                .addAction(cancel)
            ReplyState.Working -> message("Getting your message…").setLoading(true)
            ReplyState.Sending -> message("Sending…").setLoading(true)
            is ReplyState.Confirm -> message(state.text)
                .addAction(action("Send", primary = true) { session.send() })
                .addAction(cancel)
            ReplyState.NothingHeard -> message("Didn't catch anything.")
                .addAction(action("Try again", primary = true) { session.retry() })
                .addAction(cancel)
            ReplyState.VoiceOnly -> message("Couldn't turn that into text. Send it as a voice message?")
                .addAction(action("Send voice", primary = true) { session.send() })
                .addAction(action("Discard") { session.cancel() })
            is ReplyState.SendFailed -> message("Couldn't send it.")
                .addAction(action("Try again", primary = true) { session.send() })
                .addAction(cancel)
            ReplyState.Sent -> message("Sent")
            ReplyState.Cancelled -> message("Cancelled")
        }.build()
    }
}
