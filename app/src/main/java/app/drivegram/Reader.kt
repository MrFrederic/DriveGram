package app.drivegram

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** What the car and phone screens need from playback. Reader is the real one; the car screen tests use a fake. */
interface Playback {
    val playingChat: StateFlow<Long?>
    /** True while [playingChat] is still fetching and preparing, before the first word is spoken. */
    val loading: StateFlow<Boolean>
    /** Starts reading [chatId], or stops if it's the one playing. */
    fun toggle(chatId: Long)
}

/** Play: reads a chat aloud. All coroutines run on the main thread; network calls switch to IO inside the engines. */
class Reader(private val context: Context, private val tg: Telegram, private val settings: Settings) : Playback {
    private val _playingChat = MutableStateFlow<Long?>(null)
    override val playingChat: StateFlow<Long?> = _playingChat
    private val _loading = MutableStateFlow(false)
    override val loading: StateFlow<Boolean> = _loading

    private val scope = MainScope()
    private var job: Job? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    val phoneVoice by lazy { AndroidTextToSpeech(context) }

    /** Stops speech and every generation in flight (LLM and synthesis requests are cancelled, not left to finish). */
    fun stop() {
        job?.cancel()
        job = null
        _playingChat.value = null
        _loading.value = false
    }

    /** The voice picked in Settings: OpenRouter's if chosen and a key is saved, else the phone's. */
    fun voice(): TextToSpeech {
        val key = settings.openRouterKey
        return if (key != null && settings.ttsEngine == Settings.TtsEngine.OPENROUTER) {
            OpenRouterTextToSpeech(OpenRouter(key), settings.ttsModel, settings.ttsVoice, settings)
        } else {
            phoneVoice
        }
    }

    override fun toggle(chatId: Long) {
        if (_playingChat.value == chatId) stop() else play(chatId)
    }

    /**
     * Only the newest job owns the playing/loading state: a cancelled one finishing late (Stop then Play straight
     * away, even on the same chat) must not clear the state of the one that replaced it.
     */
    fun play(chatId: Long) {
        if (_playingChat.value == chatId) return // already reading it
        val previous = job
        previous?.cancel()
        _playingChat.value = chatId
        _loading.value = true
        val current = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join() // let the old one release the voice and audio focus before we start talking
            try {
                read(chatId)
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    _playingChat.value = null
                    _loading.value = false
                }
            }
        }
        job = current
        current.start()
    }

    private suspend fun read(chatId: Long) {
        val chat = tg.chat(chatId) ?: return
        val lang = phoneLang()
        val phrases = Phrases.of(lang)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS && _playingChat.value == chatId) toggle(chatId) }
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return // e.g. during a phone call
        try {
            val unreadCount = chat.unreadCount
            val lastRead = chat.lastReadInboxMessageId
            val history = try {
                if (unreadCount > 0) tg.history(chatId, minOf(unreadCount, MAX_UNREAD_TO_READ) + HISTORY_SLACK, lastRead)
                else tg.history(chatId, settings.lastN + HISTORY_SLACK)
            } catch (e: TdException) {
                debugLog("history", e)
                _loading.value = false
                phoneVoice.say(phrases.loadFailed, lang)
                return
            }
            val toRead = messagesToRead(history, unreadCount, lastRead, settings.lastN)
            if (toRead.isEmpty()) {
                _loading.value = false
                phoneVoice.say(phrases.noMessages, lang)
                return
            }
            val unread = toRead.any { !it.isOutgoing && it.id > lastRead } && unreadCount > 0
            val group = chat.type is TdApi.ChatTypeBasicGroup || (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == false
            val names = if (!group) emptyMap() else toRead.map { it.senderId }.distinctBy(::senderKey).associate { senderKey(it) to tg.senderName(it) }

            val header = Spoken(0, null, phrases.header(toRead.size, unread, unreadCount), bodyIsUserText = false, lang = lang)
            val items = listOf(header) + spokenMessages(toRead, group, names, lang)
            speakAll(items, phrases, lang) { id -> if (unread && id != 0L) markRead(chatId, id) }
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private class Prepared(val speech: Speech, val text: String, val lang: String, val notice: String?)

    /**
     * Prepares the next [PREPARE_AHEAD] items (LLM + synthesis) while one plays, so a slow rewrite doesn't leave a
     * gap. Engines are picked from settings once per Play. Everything runs on the main thread, so the shared
     * fallback flags below need no locking.
     */
    private suspend fun speakAll(items: List<Spoken>, phrases: Phrases, lang: String, onSpoken: suspend (Long) -> Unit) = coroutineScope {
        val api = settings.openRouterKey?.let(::OpenRouter)
        val rewriter: TextRewriter? = if (api != null && settings.readAloudLlm) OpenRouterRewriter(api, settings.llmModel) else null
        var voice = voice()
        var rewriteFailed = false

        suspend fun prepare(item: Spoken): Prepared {
            var notice: String? = null
            var body = item.body
            if (item.bodyIsUserText && rewriter != null && !rewriteFailed) {
                body = try {
                    rewriter.prepareForSpeech(item.body, item.lang)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    debugLog("rewrite", e)
                    // ponytail: one failure turns the LLM off for the rest of this Play (ones already running finish)
                    if (!rewriteFailed) notice = phrases.rewriteFailed
                    rewriteFailed = true
                    item.body
                }
            }
            val text = item.text(body)
            val speech = try {
                voice.prepare(text, item.lang)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                debugLog("tts", e)
                if (voice !== phoneVoice) notice = listOfNotNull(notice, phrases.voiceFailed).joinToString(" ")
                voice = phoneVoice
                phoneVoice.prepare(text, item.lang)
            }
            return Prepared(speech, text, item.lang, notice)
        }

        val prepared = items.map { async(start = CoroutineStart.LAZY) { prepare(it) } }
        for (i in items.indices) {
            prepared.subList(i, minOf(i + 1 + PREPARE_AHEAD, items.size)).forEach { it.start() }
            val current = prepared[i].await()
            _loading.value = false
            current.notice?.let { phoneVoice.say(it, lang) }
            try {
                current.speech.play()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                debugLog("playback", e)
                phoneVoice.say(current.text, current.lang) // never skip a message because the network voice failed
            }
            onSpoken(items[i].messageId)
        }
    }

    private suspend fun markRead(chatId: Long, messageId: Long) {
        try {
            tg.send(TdApi.ViewMessages(chatId, longArrayOf(messageId), null, true))
        } catch (e: TdException) {
            debugLog("markRead", e)
        }
    }

    private fun debugLog(what: String, e: Exception) {
        // Release builds log nothing. Debug logs the exception type, plus the message only for exceptions we know
        // carry no message text (OpenRouter's error and our rewrite check); a JSON parse error, say, can quote content.
        val detail = if (e is OpenRouterException || e is IllegalArgumentException) e.message else ""
        if (BuildConfig.DEBUG) Log.w("DriveGram", "$what failed: ${e.javaClass.simpleName} $detail")
    }

    private companion object {
        /** Extra history to fetch so service and outgoing messages don't eat into what's read. */
        const val HISTORY_SLACK = 20
        /** ponytail: items prepared ahead of the one playing. More means fewer gaps but paying for ones you may skip. */
        const val PREPARE_AHEAD = 3
    }
}
