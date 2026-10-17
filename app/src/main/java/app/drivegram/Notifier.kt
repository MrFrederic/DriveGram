package app.drivegram

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import app.drivegram.car.DriveGramCarService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi

/**
 * New-message notifications while a car is connected, as MessagingStyle with Reply and Mark as read, which Android
 * Auto reads out and answers by voice. TDLib's notification manager decides what to notify: it applies each chat's
 * mute setting and the defaults for private chats, groups and channels, and drops a notification once the chat is
 * read anywhere. This class only draws its groups.
 * Only messages from this drive: unread ones from before the car connected don't all go off at once.
 * ponytail: works while DriveGram's process runs (it does while the car app is open); FCM push if that's not enough.
 */
class Notifier(private val context: Context, private val tg: Telegram) {
    private class Group(val chatId: Long, val notifications: LinkedHashMap<Int, TdApi.Notification> = LinkedHashMap())

    private val groups = HashMap<Int, Group>() // by TDLib notification group id; touched on the main thread only
    private val manager = NotificationManagerCompat.from(context)
    private val scope = MainScope()
    /** Seconds since epoch when the car connected; null while no car is connected. */
    private var driveStarted: Long? = null

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH).setName("New messages").build(),
        )
        scope.launch { for (update in tg.notificationUpdates) onUpdate(update) }
        scope.launch {
            tg.auth.collect {
                if (it !is TdApi.AuthorizationStateReady) return@collect
                // TDLib only keeps notification groups if asked to; 0 (the default) turns them off.
                runCatching { tg.send(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(10))) }
                // TDLib holds notifications back (1.5 s, or 30 s while you're online on another device) in case the
                // message gets read elsewhere first. In the car you want them now.
                runCatching { tg.send(TdApi.SetOption("notification_default_delay_ms", TdApi.OptionValueInteger(0))) }
                runCatching { tg.send(TdApi.SetOption("notification_cloud_delay_ms", TdApi.OptionValueInteger(0))) }
            }
        }
        CarConnection(context).type.observeForever { type ->
            val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
            if (connected && driveStarted == null) {
                driveStarted = System.currentTimeMillis() / 1000
            } else if (!connected && driveStarted != null) {
                driveStarted = null
                groups.keys.forEach { manager.cancel(it) }
            }
        }
    }

    private suspend fun onUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateActiveNotifications -> {
                groups.keys.forEach { manager.cancel(it) }
                groups.clear()
                update.groups.forEach { g -> groups[g.id] = Group(g.chatId).apply { g.notifications.forEach { notifications[it.id] = it } } }
                groups.keys.toList().forEach { render(it) }
            }
            is TdApi.UpdateNotificationGroup -> {
                val group = groups.getOrPut(update.notificationGroupId) { Group(update.chatId) }
                update.removedNotificationIds.forEach { group.notifications.remove(it) }
                update.addedNotifications.forEach { group.notifications[it.id] = it }
                if (group.notifications.isEmpty()) groups.remove(update.notificationGroupId)
                render(update.notificationGroupId, alert = update.addedNotifications.any { !it.isSilent })
            }
            is TdApi.UpdateNotification -> {
                groups[update.notificationGroupId]?.notifications?.set(update.notification.id, update.notification) ?: return
                render(update.notificationGroupId, alert = false)
            }
            else -> {}
        }
    }

    private suspend fun render(groupId: Int, alert: Boolean = false) {
        val since = driveStarted ?: return
        val group = groups[groupId]
        val messages = group?.notifications?.values
            ?.filter { it.date >= since }
            ?.mapNotNull { n -> (n.type as? TdApi.NotificationTypeNewMessage)?.let { n to it } }
            .orEmpty()
        val chat = group?.let { tg.chat(it.chatId) }
        if (chat == null || messages.isEmpty()) {
            manager.cancel(groupId)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val phrases = Phrases.of(phoneLang())
        val isGroup = chat.type is TdApi.ChatTypeBasicGroup || (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == false
        if (Avatars.missing(chat, AVATAR_PX)) Avatars.load(tg, chat, AVATAR_PX)
        val chatIcon = IconCompat.createWithBitmap(Avatars.now(chat, AVATAR_PX))

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(phrases.you).build())
            .setConversationTitle(if (isGroup) carTitle(chat) else null)
            .setGroupConversation(isGroup)
        messages.forEach { (notification, type) ->
            val message = type.message
            val sender = Person.Builder()
                .setKey(senderKey(message.senderId))
                .setName(if (isGroup) tg.senderName(message.senderId).ifBlank { phrases.someone } else carTitle(chat))
                .apply { if (!isGroup) setIcon(chatIcon) }
                .build()
            style.addMessage(if (type.showPreview) preview(message) else phrases.newMessage, notification.date * 1000L, sender)
        }

        val ids = messages.map { it.second.message.id }.toLongArray()
        fun action(name: String, mutable: Boolean) = PendingIntent.getBroadcast(
            context,
            (groupId * 2) + if (name == ACTION_REPLY) 0 else 1,
            Intent(context, NotificationReceiver::class.java).setAction(name)
                .putExtra(EXTRA_CHAT, chat.id).putExtra(EXTRA_GROUP, groupId).putExtra(EXTRA_MESSAGES, ids),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_mic, "Reply", action(ACTION_REPLY, mutable = true)) // RemoteInput fills it in
            .addRemoteInput(RemoteInput.Builder(EXTRA_TEXT).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_check, "Mark as read", action(ACTION_MARK_READ, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // In the car the buttons open DriveGram's own Play and reply flow instead of Android Auto's built-in one.
        fun carApp(action: String, code: Int) = CarPendingIntent.getCarApp(
            context, code,
            Intent(action).setComponent(ComponentName(context, DriveGramCarService::class.java)).putExtra(EXTRA_CHAT, chat.id),
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (lastNotification, lastType) = messages.last()
        val lastText = if (lastType.showPreview) preview(lastType.message) else phrases.newMessage
        val car = CarAppExtender.Builder()
            .setContentTitle(carTitle(chat))
            .setContentText(if (isGroup) "${tg.senderName(lastType.message.senderId).ifBlank { phrases.someone }}: $lastText" else lastText)
            .setSmallIcon(R.drawable.ic_chats)
            .setLargeIcon(Avatars.now(chat, AVATAR_PX))
            .setContentIntent(carApp(Intent.ACTION_VIEW, groupId * 4 + 2))
            .addAction(R.drawable.ic_play, "Play", carApp(CAR_PLAY, groupId * 4 + 3))
            .addAction(R.drawable.ic_mic, "Reply", carApp(CAR_REPLY, groupId * 4 + 4))
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setWhen(lastNotification.date * 1000L)
            .setSmallIcon(R.drawable.ic_chats)
            .setLargeIcon(Avatars.now(chat, AVATAR_PX))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .addAction(reply)
            .addAction(markRead)
            .extend(car)
        CarNotificationManager.from(context).notify(groupId, notification)
    }

    companion object {
        private const val CHANNEL = "messages"
        private const val AVATAR_PX = 128
        const val CAR_PLAY = "app.drivegram.car.PLAY"
        const val CAR_REPLY = "app.drivegram.car.REPLY"
        const val ACTION_REPLY = "app.drivegram.REPLY"
        const val ACTION_MARK_READ = "app.drivegram.MARK_READ"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_GROUP = "group"
        const val EXTRA_MESSAGES = "messages"
        const val EXTRA_TEXT = "text"
    }
}

/** Reply and Mark as read from a notification, on the phone or in the car. */
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra(Notifier.EXTRA_CHAT, 0)
        val groupId = intent.getIntExtra(Notifier.EXTRA_GROUP, 0)
        val ids = intent.getLongArrayExtra(Notifier.EXTRA_MESSAGES) ?: longArrayOf()
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.EXTRA_TEXT)?.toString()?.trim()
        val tg = App.instance.telegram
        val pending = goAsync()
        MainScope().launch {
            try {
                // A receiver gets about 10 s; TDLib keeps sending in the background if Telegram is slower than that.
                withTimeoutOrNull(9_000) {
                    if (intent.action == Notifier.ACTION_REPLY && !text.isNullOrEmpty()) tg.sendText(chatId, text)
                    tg.send(TdApi.ViewMessages(chatId, ids, null, true))
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("DriveGram", "notification ${intent.action} failed: ${e.javaClass.simpleName}")
            } finally {
                // Android Auto waits for the notification to change after a reply; TDLib also drops it once read.
                NotificationManagerCompat.from(context).cancel(groupId)
                pending.finish()
            }
        }
    }
}
