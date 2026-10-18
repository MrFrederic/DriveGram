package app.drivegram.car

import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.drivegram.ChatSource
import app.drivegram.Playback
import app.drivegram.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** Builds the real car screens against fake data and checks the templates they hand to the host. */
@RunWith(RobolectricTestRunner::class)
class CarScreensTest {
    private val carContext = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
    private val source = FakeSource()
    private val playback = FakePlayback()

    private class FakeSource : ChatSource {
        override val auth = MutableStateFlow<TdApi.AuthorizationState?>(TdApi.AuthorizationStateReady())
        override val folders = MutableStateFlow<List<TdApi.ChatFolderInfo>>(emptyList())
        override val chatsVersion = MutableStateFlow(0L)
        var chats = listOf<TdApi.Chat>()
        override fun chat(id: Long) = chats.find { it.id == id }
        override fun chatsIn(list: TdApi.ChatList, limit: Int) = chats.take(limit)
        override fun unreadChats(list: TdApi.ChatList) = 0
        override suspend fun load(list: TdApi.ChatList, limit: Int) {}
        override suspend fun photoPath(file: TdApi.File): String? = null
    }

    private class FakePlayback : Playback {
        override val playingChat = MutableStateFlow<Long?>(null)
        override val loading = MutableStateFlow(false)
        val toggled = mutableListOf<Long>()
        override fun toggle(chatId: Long) { toggled += chatId }
    }

    private val now = (System.currentTimeMillis() / 1000).toInt()

    /** [at] is the last message's time; by default it's "during this drive". */
    private fun chat(id: Long, title: String, unread: Int = 0, at: Int = now + 60) = TdApi.Chat().apply {
        this.id = id
        this.title = title
        unreadCount = unread
        positions = arrayOf()
        lastMessage = TdApi.Message().apply { date = at }
    }

    private fun folder(id: Int, name: String) = TdApi.ChatFolderInfo().apply {
        this.id = id
        this.name = TdApi.ChatFolderName(TdApi.FormattedText(name, arrayOf()), false)
    }

    private fun render(controller: ScreenController): Template {
        ShadowLooper.idleMainLooper()
        return controller.templatesReturned.last()
    }

    private fun root() = ScreenController(RootScreen(carContext, source, playback)).apply { moveToState(Lifecycle.State.RESUMED) }

    private fun rows(template: Template): List<Row> = (template as ListTemplate).singleList!!.items.map { it as Row }
    /** The first screen's chat rows, without the "All chats and folders" row above them. */
    private fun chatRows(template: Template): List<Row> = rows(template).drop(1)

    @Test fun `logged out shows the log-in message`() {
        source.auth.value = TdApi.AuthorizationStateWaitPhoneNumber()
        val template = render(root())
        assertTrue(template is MessageTemplate)
    }

    @Test fun `this drive lists only chats with messages since the car connected`() {
        source.chats = listOf(chat(1, "Anna"), chat(2, "Old group", unread = 5, at = now - 3600))
        assertEquals(listOf("All chats and folders", "Anna"), rows(render(root())).map { it.title.toString() })
    }

    @Test fun `this drive without new messages still leads to the folders`() {
        source.chats = listOf(chat(2, "Old group", unread = 5, at = now - 3600))
        val row = rows(render(root())).single()
        assertEquals("All chats and folders", row.title.toString())
        assertEquals("No new messages during this drive", row.texts.single().toString())
        assertTrue(row.isBrowsable)
    }

    @Test fun `folders lists all chats, then each folder`() {
        source.folders.value = listOf(folder(1, "DMs"), folder(2, "Groups"))
        val controller = ScreenController(FoldersScreen(carContext, source, playback)).apply { moveToState(Lifecycle.State.RESUMED) }
        assertEquals(listOf("All chats", "DMs", "Groups"), rows(render(controller)).map { it.title.toString() })
    }

    @Test fun `reply screen asks before sending`() {
        val session = app.drivegram.ReplySession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            phrases = app.drivegram.Phrases.En,
            autoSend = false,
            record = { _, _ -> app.drivegram.Recorded(byteArrayOf(1), heardSpeech = true) },
            transcribe = { "see you at 7" },
            cleanUp = null,
            sendText = {},
            sendVoice = {},
            speak = { _, _ -> },
        ).also { it.start() }
        val controller = ScreenController(ReplyScreen(carContext, "Anna", session)).apply { moveToState(Lifecycle.State.RESUMED) }
        val template = render(controller) as MessageTemplate
        assertEquals("see you at 7", template.message.toString())
        assertEquals(listOf("Send", "Cancel"), template.actions.map { it.title.toString() })
    }

    @Test fun `rows have one action, the unread count in text, and a fallback title`() {
        source.chats = listOf(chat(1, "Anna", unread = 3), chat(2, ""))
        val rows = chatRows(render(root()))
        // Android Auto 17.8 drops a second row action, so rows must never have more than one.
        assertTrue(rows.all { it.actions.size == 1 && it.onClickDelegate != null })
        assertTrue(rows[0].texts.single().toString().startsWith("● 3 unread"))
        assertEquals("Deleted account", rows[1].title.toString())
    }

    @Test fun `the playing chat shows stop`() {
        source.chats = listOf(chat(1, "Anna"), chat(2, "Max"))
        playback.playingChat.value = 1
        val icons = chatRows(render(root())).map { it.actions.single().icon!!.icon!!.resId }
        assertEquals(listOf(R.drawable.ic_stop, R.drawable.ic_play), icons)
    }

    @Test fun `a chat that is still loading says so`() {
        source.chats = listOf(chat(1, "Anna", unread = 2))
        playback.playingChat.value = 1
        playback.loading.value = true
        assertEquals("Loading messages…", chatRows(render(root())).single().texts.single().toString())
    }
}
