package app.drivegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudTest {
    private fun text(t: String) = TdApi.FormattedText(t, arrayOf())

    private fun msg(id: Long, body: String = "m$id", from: Long = 1, out: Boolean = false, content: TdApi.MessageContent? = null) =
        TdApi.Message().apply {
            this.id = id
            isOutgoing = out
            senderId = TdApi.MessageSenderUser(from)
            this.content = content ?: TdApi.MessageText().apply { this.text = text(body) }
        }

    /** History as TDLib returns it: newest first. */
    private fun history(vararg ids: Long) = ids.sortedDescending().map { msg(it) }

    private fun ids(messages: List<TdApi.Message>) = messages.map { it.id }

    @Test fun `unread messages are read oldest first`() {
        val picked = messagesToRead(history(1, 2, 3, 4, 5), unreadCount = 3, lastReadInboxId = 2, lastN = 3)
        assertEquals(listOf(3L, 4L, 5L), ids(picked))
    }

    @Test fun `nothing unread reads the last N`() {
        val picked = messagesToRead(history(1, 2, 3, 4, 5), unreadCount = 0, lastReadInboxId = 5, lastN = 2)
        assertEquals(listOf(4L, 5L), ids(picked))
    }

    @Test fun `my own messages after the read mark aren't unread`() {
        val newestFirst = listOf(msg(5), msg(4, out = true), msg(3), msg(2))
        assertEquals(listOf(3L, 5L), ids(messagesToRead(newestFirst, unreadCount = 2, lastReadInboxId = 2, lastN = 3)))
    }

    @Test fun `too many unread keeps the newest ones`() {
        val picked = messagesToRead(history(*(1L..50L).toList().toLongArray()), unreadCount = 50, lastReadInboxId = 0, lastN = 3, maxUnread = 30)
        assertEquals((21L..50L).toList(), ids(picked))
    }

    @Test fun `service messages are skipped`() {
        val newestFirst = listOf(msg(3), msg(2, content = TdApi.MessagePinMessage()), msg(1))
        assertEquals(listOf(1L, 3L), ids(messagesToRead(newestFirst, unreadCount = 0, lastReadInboxId = 3, lastN = 3)))
    }

    @Test fun `unread count with only service messages falls back to last N`() {
        val newestFirst = listOf(msg(3, content = TdApi.MessageChatAddMembers()), msg(2), msg(1))
        assertEquals(listOf(1L, 2L), ids(messagesToRead(newestFirst, unreadCount = 1, lastReadInboxId = 2, lastN = 2)))
    }

    @Test fun `groups name the sender only when it changes`() {
        val messages = listOf(msg(1, "hi", from = 1), msg(2, "there", from = 1), msg(3, "yo", from = 2), msg(4, "ok", out = true))
        val names = mapOf("user:1" to "Anna", "user:2" to "Max")
        val spoken = spokenMessages(messages, isGroup = true, names = names, defaultLang = "en").map { it.text() }
        assertEquals(listOf("Anna: hi", "there", "Max: yo", "You: ok"), spoken)
    }

    @Test fun `private chats never name the sender`() {
        val spoken = spokenMessages(listOf(msg(1, "hi", from = 1), msg(2, "yo", from = 2)), isGroup = false, names = emptyMap(), defaultLang = "en")
        assertEquals(listOf("hi", "yo"), spoken.map { it.text() })
    }

    @Test fun `unknown sender in a group is someone`() {
        val spoken = spokenMessages(listOf(msg(1, "hi")), isGroup = true, names = emptyMap(), defaultLang = "en")
        assertEquals("Someone: hi", spoken.single().text())
    }

    @Test fun `non-text messages get a short description`() {
        val voice = TdApi.MessageVoiceNote().apply { voiceNote = TdApi.VoiceNote().apply { duration = 12 }; caption = text("") }
        val sticker = TdApi.MessageSticker().apply { this.sticker = TdApi.Sticker().apply { emoji = "👍" } }
        val photo = TdApi.MessagePhoto().apply { caption = text("") }
        val spoken = spokenMessages(listOf(msg(1, content = voice), msg(2, content = sticker), msg(3, content = photo)), false, emptyMap(), "en")
        assertEquals(listOf("Voice message, 12 seconds", "Sticker 👍", "Photo"), spoken.map { it.text() })
        assertEquals(listOf(false, false, false), spoken.map { it.bodyIsUserText })
    }

    @Test fun `captions follow the description and go to the LLM`() {
        val photo = TdApi.MessagePhoto().apply { caption = text("Look at this") }
        val spoken = spokenMessages(listOf(msg(1, content = photo)), false, emptyMap(), "ru").single()
        assertEquals("Photo: Look at this", spoken.text())
        assertEquals("Look at this", spoken.body) // only the caption goes to the LLM, not our "Photo" label
        assertEquals("en", spoken.lang)
        assertEquals(true, spoken.bodyIsUserText)
    }

    @Test fun `media without text uses the phone language`() {
        val voice = TdApi.MessageVoiceNote().apply { voiceNote = TdApi.VoiceNote().apply { duration = 95 }; caption = text("") }
        assertEquals("Голосовое сообщение, 2 минуты", spokenMessages(listOf(msg(1, content = voice)), false, emptyMap(), "ru").single().text())
    }

    @Test fun `language is guessed per message`() {
        assertEquals("ru", detectLang("Привет, как дела?"))
        assertEquals("en", detectLang("See you at 7"))
        assertEquals("ru", detectLang("Скинь pdf плиз"))
        assertNull(detectLang("👍 123"))
    }

    @Test fun `russian plurals`() {
        val forms = listOf(1, 2, 5, 11, 21, 22, 25, 111).map { Phrases.Ru.plural(it, "сообщение", "сообщения", "сообщений") }
        assertEquals(listOf("сообщение", "сообщения", "сообщений", "сообщений", "сообщение", "сообщения", "сообщений", "сообщений"), forms)
    }

    @Test fun `headers`() {
        assertEquals("3 unread messages.", Phrases.En.header(3, unread = true, totalUnread = 3))
        assertEquals("53 unread, reading the latest 30.", Phrases.En.header(30, unread = true, totalUnread = 53))
        assertEquals("Last 3 messages.", Phrases.En.header(3, unread = false, totalUnread = 0))
        assertEquals("2 непрочитанных сообщения.", Phrases.Ru.header(2, unread = true, totalUnread = 2))
    }

    @Test fun `crossed-out words and spoilers are spelled out`() {
        fun entity(offset: Int, length: Int, type: TdApi.TextEntityType) = TdApi.TextEntity(offset, length, type)
        val formatted = TdApi.FormattedText(
            "Я ненавижу люблю понедельники, а в конце все умрут",
            arrayOf(entity(2, 8, TdApi.TextEntityTypeStrikethrough()), entity(41, 9, TdApi.TextEntityTypeSpoiler())),
        )
        assertEquals("Я (зачёркнуто: ненавижу) люблю понедельники, а в конце (спойлер)", spokenFormatting(formatted, Phrases.Ru))
        val message = msg(1, content = TdApi.MessageText().apply { text = formatted })
        assertEquals(true, spokenMessages(listOf(message), false, emptyMap(), "en").single().body.contains("(зачёркнуто: ненавижу)"))
    }
}
