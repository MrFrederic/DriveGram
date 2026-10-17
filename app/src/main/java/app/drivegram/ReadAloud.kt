package app.drivegram

import org.drinkless.tdlib.TdApi

// Pure logic behind Play: which messages to read and what to say for each. No Android, no TDLib calls.

/** ponytail: cap on unread messages read in one go, so a busy group doesn't talk for an hour. Make it a setting if 30 is wrong. */
const val MAX_UNREAD_TO_READ = 30

/**
 * Picks what Play reads, oldest first: every unread incoming message (the newest [maxUnread] of them), or the last
 * [lastN] messages when nothing is unread. Service messages ("X joined", pins) are skipped.
 * [newestFirst] is history as TDLib returns it.
 */
fun messagesToRead(
    newestFirst: List<TdApi.Message>,
    unreadCount: Int,
    lastReadInboxId: Long,
    lastN: Int,
    maxUnread: Int = MAX_UNREAD_TO_READ,
): List<TdApi.Message> {
    val readable = newestFirst.filter { isReadable(it.content) }
    val unread = if (unreadCount > 0) readable.filter { !it.isOutgoing && it.id > lastReadInboxId }.take(maxUnread) else emptyList()
    // Unread count can be > 0 with nothing readable after the read mark (only service messages): read the last N then.
    return unread.ifEmpty { readable.take(lastN) }.reversed()
}

/**
 * One message as it will be spoken. Only [body] goes through the LLM, and only when [bodyIsUserText]; [description]
 * ("Photo") is ours and is put in front afterwards, so the LLM never sees a label it would try to "fix" or explain.
 */
data class Spoken(
    val messageId: Long,
    val sender: String?,
    val body: String,
    val bodyIsUserText: Boolean,
    val lang: String,
    val description: String? = null,
) {
    fun text(body: String = this.body): String {
        val content = listOfNotNull(description, body.takeIf { it.isNotBlank() }).joinToString(": ")
        return if (sender == null) content else "$sender: $content"
    }
}

/**
 * Turns [messages] (oldest first) into what to say. In groups the sender is named when it changes from the previous
 * message; private chats and channels never name the sender. [names] maps sender keys from [senderKey] to names.
 */
fun spokenMessages(
    messages: List<TdApi.Message>,
    isGroup: Boolean,
    names: Map<String, String>,
    defaultLang: String,
): List<Spoken> {
    var previousSender: String? = null
    return messages.map { message ->
        val formatted = formattedText(message.content)
        val lang = formatted?.let { detectLang(it.text) } ?: defaultLang
        val phrases = Phrases.of(lang)
        val userText = formatted?.let { spokenFormatting(it, phrases) }.orEmpty()
        val key = if (message.isOutgoing) "me" else senderKey(message.senderId)
        val sender = when {
            !isGroup || key == previousSender -> null
            message.isOutgoing -> phrases.you
            else -> names[key]?.takeIf { it.isNotBlank() } ?: phrases.someone
        }
        previousSender = key
        Spoken(message.id, sender, userText, bodyIsUserText = userText.isNotBlank(), lang = lang, description = phrases.describe(message.content))
    }
}

fun senderKey(sender: TdApi.MessageSender): String = when (sender) {
    is TdApi.MessageSenderUser -> "user:${sender.userId}"
    is TdApi.MessageSenderChat -> "chat:${sender.chatId}"
    else -> "unknown"
}

/** Language for our own phrases (headers, descriptions without text): the phone's, if we have it, else English. */
fun phoneLang(): String = if (java.util.Locale.getDefault().language == "ru") "ru" else "en"

/** "ru" if the text is mostly Cyrillic, "en" if mostly Latin, null if it has no letters. */
fun detectLang(text: String): String? {
    var cyrillic = 0
    var latin = 0
    for (c in text) {
        when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.CYRILLIC -> cyrillic++
            Character.UnicodeScript.LATIN -> latin++
            else -> {}
        }
    }
    return when {
        cyrillic == 0 && latin == 0 -> null
        cyrillic >= latin -> "ru"
        else -> "en"
    }
}

private fun formattedText(content: TdApi.MessageContent): TdApi.FormattedText? = when (content) {
    is TdApi.MessageText -> content.text
    is TdApi.MessagePhoto -> content.caption
    is TdApi.MessageVideo -> content.caption
    is TdApi.MessageVoiceNote -> content.caption
    is TdApi.MessageAudio -> content.caption
    is TdApi.MessageDocument -> content.caption
    is TdApi.MessageAnimation -> content.caption
    else -> null
}

/**
 * Plain text with the formatting a listener needs spelled out: crossed-out words (often the joke: "I ~~hate~~ love
 * Mondays") become "(crossed out: hate)", spoilers become "(spoiler)" so reading aloud doesn't give them away.
 * Without this the LLM sees "I hate love Mondays" and starts explaining instead of tidying.
 */
fun spokenFormatting(text: TdApi.FormattedText, phrases: Phrases): String {
    val out = StringBuilder()
    var pos = 0
    text.entities
        .filter { it.type is TdApi.TextEntityTypeStrikethrough || it.type is TdApi.TextEntityTypeSpoiler }
        .sortedBy { it.offset }
        .forEach { entity ->
            if (entity.offset < pos) return@forEach // overlaps the previous one
            out.append(text.text, pos, entity.offset)
            val inner = text.text.substring(entity.offset, entity.offset + entity.length)
            out.append(if (entity.type is TdApi.TextEntityTypeSpoiler) "(${phrases.spoiler})" else "(${phrases.crossedOut}: $inner)")
            pos = entity.offset + entity.length
        }
    return out.append(text.text, pos, text.text.length).toString()
}

private fun isReadable(content: TdApi.MessageContent): Boolean = when (content) {
    is TdApi.MessageText, is TdApi.MessagePhoto, is TdApi.MessageVideo, is TdApi.MessageVoiceNote,
    is TdApi.MessageVideoNote, is TdApi.MessageAudio, is TdApi.MessageDocument, is TdApi.MessageSticker,
    is TdApi.MessageAnimation, is TdApi.MessageLocation, is TdApi.MessageVenue, is TdApi.MessageContact,
    is TdApi.MessagePoll, is TdApi.MessageDice, is TdApi.MessageAnimatedEmoji -> true
    else -> false
}

/** Short spoken phrases. Russian or English, picked per message by its language. */
sealed class Phrases {
    abstract val lang: String
    abstract val you: String
    abstract val someone: String
    abstract val noMessages: String
    abstract val loadFailed: String
    abstract val rewriteFailed: String
    abstract val voiceFailed: String
    abstract val nothingHeard: String
    abstract val transcribeFailed: String
    abstract val cleanUpFailed: String
    abstract val sendQuestion: String
    abstract val sent: String
    abstract val sendFailed: String
    abstract val crossedOut: String
    abstract val spoiler: String
    /** Notification text when your Telegram settings hide message previews. */
    abstract val newMessage: String
    /** Said before the messages. No chat name: you just picked the chat. */
    abstract fun header(count: Int, unread: Boolean, totalUnread: Int): String
    protected abstract fun duration(seconds: Int): String
    protected abstract val photo: String
    protected abstract val video: String
    protected abstract val voice: String
    protected abstract val videoMessage: String
    protected abstract val audio: String
    protected abstract val file: String
    protected abstract val sticker: String
    protected abstract val gif: String
    protected abstract val location: String
    protected abstract val place: String
    protected abstract val contact: String
    protected abstract val poll: String

    /** Spoken description for non-text content; null for plain text. Captions are added by the caller. */
    fun describe(content: TdApi.MessageContent): String? = when (content) {
        is TdApi.MessageText -> null
        is TdApi.MessagePhoto -> photo
        is TdApi.MessageVideo -> "$video, ${duration(content.video.duration)}"
        is TdApi.MessageVoiceNote -> "$voice, ${duration(content.voiceNote.duration)}"
        is TdApi.MessageVideoNote -> "$videoMessage, ${duration(content.videoNote.duration)}"
        is TdApi.MessageAudio -> listOf(audio, listOf(content.audio.performer, content.audio.title).filter { it.isNotBlank() }.joinToString(" — "))
            .filter { it.isNotBlank() }.joinToString(": ")
        is TdApi.MessageDocument -> "$file ${content.document.fileName}".trim()
        is TdApi.MessageSticker -> "$sticker ${content.sticker.emoji}".trim()
        is TdApi.MessageAnimation -> gif
        is TdApi.MessageLocation -> location
        is TdApi.MessageVenue -> "$place: ${content.venue.title}"
        is TdApi.MessageContact -> "$contact: ${content.contact.firstName} ${content.contact.lastName}".trim()
        is TdApi.MessagePoll -> "$poll: ${content.poll.question.text}"
        is TdApi.MessageDice -> content.emoji
        is TdApi.MessageAnimatedEmoji -> content.emoji
        else -> null
    }

    object En : Phrases() {
        override val lang = "en"
        override val nothingHeard = "I didn't catch anything."
        override val transcribeFailed = "Couldn't turn that into text. You can send it as a voice message."
        override val cleanUpFailed = "Couldn't tidy it up, here it is as you said it."
        override val sendQuestion = "Send?"
        override val sent = "Sent."
        override val sendFailed = "Couldn't send it."
        override val you = "You"
        override val someone = "Someone"
        override val noMessages = "No messages."
        override val loadFailed = "Couldn't load the messages."
        override val rewriteFailed = "Couldn't tidy up the text, reading it as is."
        override val voiceFailed = "The OpenRouter voice failed, switching to the phone voice."
        override val crossedOut = "crossed out"
        override val spoiler = "spoiler"
        override val newMessage = "New message"
        override fun header(count: Int, unread: Boolean, totalUnread: Int) = when {
            unread && totalUnread > count -> "$totalUnread unread, reading the latest $count."
            unread -> "$count unread ${if (count == 1) "message" else "messages"}."
            else -> "Last ${if (count == 1) "message" else "$count messages"}."
        }
        override fun duration(seconds: Int) = when {
            seconds < 60 -> "$seconds ${if (seconds == 1) "second" else "seconds"}"
            else -> ((seconds + 30) / 60).let { "$it ${if (it == 1) "minute" else "minutes"}" }
        }
        override val photo = "Photo"
        override val video = "Video"
        override val voice = "Voice message"
        override val videoMessage = "Video message"
        override val audio = "Audio"
        override val file = "File"
        override val sticker = "Sticker"
        override val gif = "GIF"
        override val location = "Location"
        override val place = "Place"
        override val contact = "Contact"
        override val poll = "Poll"
    }

    object Ru : Phrases() {
        override val lang = "ru"
        override val nothingHeard = "Ничего не слышно."
        override val transcribeFailed = "Не удалось распознать. Можно отправить голосовым сообщением."
        override val cleanUpFailed = "Не получилось обработать, вот как вы сказали."
        override val sendQuestion = "Отправить?"
        override val sent = "Отправлено."
        override val sendFailed = "Не удалось отправить."
        override val you = "Вы"
        override val someone = "Кто-то"
        override val noMessages = "Сообщений нет."
        override val loadFailed = "Не удалось загрузить сообщения."
        override val rewriteFailed = "Не получилось обработать текст, читаю как есть."
        override val voiceFailed = "Голос OpenRouter не сработал, переключаюсь на голос телефона."
        override val crossedOut = "зачёркнуто"
        override val spoiler = "спойлер"
        override val newMessage = "Новое сообщение"
        override fun header(count: Int, unread: Boolean, totalUnread: Int) = when {
            unread && totalUnread > count -> "$totalUnread ${plural(totalUnread, "непрочитанное", "непрочитанных", "непрочитанных")}, читаю последние $count."
            unread -> "$count ${plural(count, "непрочитанное сообщение", "непрочитанных сообщения", "непрочитанных сообщений")}."
            count == 1 -> "Последнее сообщение."
            else -> "Последние $count ${plural(count, "сообщение", "сообщения", "сообщений")}."
        }
        override fun duration(seconds: Int) = when {
            seconds < 60 -> "$seconds ${plural(seconds, "секунда", "секунды", "секунд")}"
            else -> ((seconds + 30) / 60).let { "$it ${plural(it, "минута", "минуты", "минут")}" }
        }
        override val photo = "Фото"
        override val video = "Видео"
        override val voice = "Голосовое сообщение"
        override val videoMessage = "Видеосообщение"
        override val audio = "Аудио"
        override val file = "Файл"
        override val sticker = "Стикер"
        override val gif = "Гифка"
        override val location = "Геолокация"
        override val place = "Место"
        override val contact = "Контакт"
        override val poll = "Опрос"

        /** Russian plural: 1 сообщение, 2 сообщения, 5 сообщений, 21 сообщение, 11 сообщений. */
        fun plural(n: Int, one: String, few: String, many: String): String {
            val mod100 = n % 100
            val mod10 = n % 10
            return when {
                mod100 in 11..14 -> many
                mod10 == 1 -> one
                mod10 in 2..4 -> few
                else -> many
            }
        }
    }

    companion object {
        fun of(lang: String): Phrases = if (lang == "ru") Ru else En
    }
}
