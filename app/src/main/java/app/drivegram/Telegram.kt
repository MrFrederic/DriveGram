package app.drivegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdException(val error: TdApi.Error) : Exception("${error.code}: ${error.message}")

/** What the car screens read. Telegram is the real one; the car screen tests use a fake. */
interface ChatSource {
    val auth: StateFlow<TdApi.AuthorizationState?>
    val folders: StateFlow<List<TdApi.ChatFolderInfo>>
    val chatsVersion: StateFlow<Long>
    fun chat(id: Long): TdApi.Chat?
    fun chatsIn(list: TdApi.ChatList, limit: Int): List<TdApi.Chat>
    fun unreadChats(list: TdApi.ChatList): Int
    suspend fun load(list: TdApi.ChatList, limit: Int)
    /** Local path of [file], downloading it first if needed; null if it can't be had. */
    suspend fun photoPath(file: TdApi.File): String?
}

/** One TDLib client for the whole process. Keeps the chat cache that TDLib expects clients to maintain from updates. */
class Telegram(private val context: Context) : ChatSource {
    private val _auth = MutableStateFlow<TdApi.AuthorizationState?>(null)
    override val auth: StateFlow<TdApi.AuthorizationState?> = _auth

    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError

    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()

    private val unreadByList = ConcurrentHashMap<String, Int>()
    private val users = ConcurrentHashMap<Long, TdApi.User>()
    /** Delivery results by the temporary id SendMessage returns: null error = delivered. Either side may come first. */
    private val sends = ConcurrentHashMap<Long, CompletableDeferred<TdApi.Error?>>()

    private val _folders = MutableStateFlow<List<TdApi.ChatFolderInfo>>(emptyList())
    override val folders: StateFlow<List<TdApi.ChatFolderInfo>> = _folders

    // ponytail: one "something changed" counter instead of per-chat flows; readers re-query the cache.
    private val _chatsVersion = MutableStateFlow(0L)
    override val chatsVersion: StateFlow<Long> = _chatsVersion

    /** TDLib's notification updates, buffered until the Notifier reads them (some arrive right at startup). */
    val notificationUpdates = Channel<TdApi.Update>(Channel.UNLIMITED)

    private val client: Client

    init {
        // Errors only: higher levels can log message content.
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create({ onUpdate(it) }, null, null)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        client.send(function) { result ->
            if (result is TdApi.Error) cont.resumeWithException(TdException(result)) else cont.resume(result as T)
        }
    }

    override fun chat(id: Long): TdApi.Chat? = chats[id]

    /** Unread chats in [list], as TDLib reports it (0 until TDLib has counted). */
    override fun unreadChats(list: TdApi.ChatList): Int = unreadByList[key(list)] ?: 0

    /** Chats in [list], most recent activity first (TDLib's own order), at most [limit]. */
    override fun chatsIn(list: TdApi.ChatList, limit: Int): List<TdApi.Chat> =
        chats.values
            .mapNotNull { chat -> chat.positions.firstOrNull { sameList(it.list, list) && it.order != 0L }?.let { chat to it.order } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

    /** Asks TDLib to load at least [limit] chats of [list] into the cache. A 404 just means everything is loaded. */
    override suspend fun load(list: TdApi.ChatList, limit: Int) {
        try {
            send(TdApi.LoadChats(list, limit))
        } catch (e: TdException) {
            if (e.error.code != 404) throw e
        }
    }

    override suspend fun photoPath(file: TdApi.File): String? {
        if (file.local.isDownloadingCompleted) return file.local.path
        val done = send(TdApi.DownloadFile(file.id, 1, 0, 0, true)) // synchronous: returns once it's downloaded
        return done.local.path.takeIf { done.local.isDownloadingCompleted }
    }

    /**
     * Newest-first history of [chatId]: [count] messages, or back to [stopAtId] (inclusive), whichever comes first.
     * TDLib returns fewer messages than asked for (often just one on the first call), hence the loop.
     */
    suspend fun history(chatId: Long, count: Int, stopAtId: Long = 0): List<TdApi.Message> {
        val messages = LinkedHashMap<Long, TdApi.Message>()
        var from = 0L
        while (true) {
            val batch = send(TdApi.GetChatHistory(chatId, from, 0, 100, false)).messages
            val before = messages.size
            batch.forEach { messages[it.id] = it }
            val oldest = messages.values.lastOrNull() ?: break
            if (messages.size == before || messages.size >= count || (stopAtId != 0L && oldest.id <= stopAtId)) break
            from = oldest.id
        }
        return messages.values.toList()
    }

    suspend fun sendText(chatId: Long, text: String) =
        deliver(TdApi.InputMessageText(TdApi.FormattedText(text, arrayOf()), null, true), chatId)

    suspend fun sendVoice(chatId: Long, file: java.io.File, durationSeconds: Int) =
        deliver(TdApi.InputMessageVoiceNote(TdApi.InputVoiceNote(TdApi.InputFileLocal(file.path), durationSeconds, null), null, null), chatId)

    /**
     * Sends and waits for Telegram to confirm delivery, so a failure can be said out loud.
     * ponytail: after 30 s without an answer it counts as sent; TDLib keeps retrying in the background.
     */
    private suspend fun deliver(content: TdApi.InputMessageContent, chatId: Long) {
        val pending = send(TdApi.SendMessage(chatId, null, null, null, null, content))
        val result = sends.computeIfAbsent(pending.id) { CompletableDeferred() }
        val error = withTimeoutOrNull(30_000) { result.await() }
        sends.remove(pending.id)
        if (error != null) throw TdException(error)
    }

    /** Display name for a message sender. Fetches users TDLib hasn't told us about yet. */
    suspend fun senderName(sender: TdApi.MessageSender): String = when (sender) {
        is TdApi.MessageSenderUser -> {
            val user = users[sender.userId] ?: runCatching { send(TdApi.GetUser(sender.userId)) }.getOrNull()
            user?.let { "${it.firstName} ${it.lastName}".trim() }.orEmpty()
        }
        is TdApi.MessageSenderChat -> chats[sender.chatId]?.title.orEmpty()
        else -> ""
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> {
                if (update.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters) sendParameters()
                _auth.value = update.authorizationState
            }
            is TdApi.UpdateNewChat -> chats[update.chat.id] = update.chat
            is TdApi.UpdateChatTitle -> chats[update.chatId]?.title = update.title
            is TdApi.UpdateChatPhoto -> chats[update.chatId]?.photo = update.photo
            is TdApi.UpdateChatLastMessage -> chats[update.chatId]?.let { chat ->
                chat.lastMessage = update.lastMessage
                update.positions.forEach { chat.setPosition(it) }
            }
            is TdApi.UpdateChatPosition -> chats[update.chatId]?.setPosition(update.position)
            is TdApi.UpdateChatReadInbox -> chats[update.chatId]?.let {
                it.unreadCount = update.unreadCount
                it.lastReadInboxMessageId = update.lastReadInboxMessageId
            }
            is TdApi.UpdateChatNotificationSettings -> chats[update.chatId]?.notificationSettings = update.notificationSettings
            is TdApi.UpdateChatFolders -> _folders.value = update.chatFolders.toList()
            is TdApi.UpdateUnreadChatCount -> unreadByList[key(update.chatList)] = update.unreadCount
            is TdApi.UpdateMessageSendSucceeded -> {
                sends.computeIfAbsent(update.oldMessageId) { CompletableDeferred() }.complete(null)
                return
            }
            is TdApi.UpdateMessageSendFailed -> {
                sends.computeIfAbsent(update.oldMessageId) { CompletableDeferred() }.complete(update.error)
                return
            }
            is TdApi.UpdateActiveNotifications, is TdApi.UpdateNotificationGroup, is TdApi.UpdateNotification -> {
                notificationUpdates.trySend(update as TdApi.Update)
                return
            }
            is TdApi.UpdateUser -> {
                users[update.user.id] = update.user
                return // names don't change any list
            }
            else -> return
        }
        _chatsVersion.value++
    }

    private fun TdApi.Chat.setPosition(position: TdApi.ChatPosition) {
        positions = positions.filterNot { sameList(it.list, position.list) }.toTypedArray().let {
            if (position.order == 0L) it else it + position
        }
    }

    private fun sendParameters() {
        val dir = context.filesDir.resolve("tdlib").absolutePath
        val params = TdApi.SetTdlibParameters().apply {
            databaseDirectory = dir
            filesDirectory = dir
            // ponytail: no DB encryption key; the database sits in app-private storage. Add a Keystore-wrapped key if that's not enough.
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = BuildConfig.TG_API_ID
            apiHash = BuildConfig.TG_API_HASH
            systemLanguageCode = java.util.Locale.getDefault().language
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        client.send(params) { if (it is TdApi.Error) _startupError.value = it.message }
    }

    companion object {
        fun key(list: TdApi.ChatList): String = when (list) {
            is TdApi.ChatListFolder -> "folder:${list.chatFolderId}"
            else -> list.constructor.toString()
        }

        fun sameList(a: TdApi.ChatList, b: TdApi.ChatList): Boolean = key(a) == key(b)
    }
}

/** Car templates throw on empty titles; TDLib gives deleted accounts an empty one. */
fun carTitle(chat: TdApi.Chat): String = chat.title.ifBlank { "Deleted account" }

/** Folder name without custom emoji: neither the car nor TTS can show them, and the fallback glyph is often a box. */
val TdApi.ChatFolderInfo.title: String
    get() {
        val text = StringBuilder(name.text.text)
        name.text.entities
            .filter { it.type is TdApi.TextEntityTypeCustomEmoji }
            .sortedByDescending { it.offset }
            .forEach { text.delete(it.offset, it.offset + it.length) }
        return text.toString().trim().ifEmpty { name.text.text }
    }
