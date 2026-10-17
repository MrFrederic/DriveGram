package app.drivegram

import android.app.Application

class App : Application() {
    val telegram by lazy { Telegram(this, settings) }
    val settings by lazy { Settings(this) }
    val reader by lazy { Reader(this, telegram, settings) }
    val replies by lazy { Replies(this, telegram, settings, reader) }
    private lateinit var notifier: Notifier

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifier = Notifier(this, telegram)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
