package app.drivegram

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Starts reply sessions with the engines picked in Settings. Shared by the car and the phone screen. */
class Replies(private val context: Context, private val tg: Telegram, private val settings: Settings, private val reader: Reader) {
    private val scope = MainScope()
    private val audio = context.getSystemService(AudioManager::class.java)

    fun start(chatId: Long, mic: MicSource): ReplySession {
        reader.stop() // don't read messages over your dictation
        val lang = phoneLang()
        val phrases = Phrases.of(lang)
        val api = settings.openRouterKey?.let(::OpenRouter)
        val stt = api?.let { OpenRouterSpeechToText(it, settings.sttModel) }
        val rewriter = if (api != null && settings.cleanUpDictation) OpenRouterRewriter(api, settings.llmModel) else null
        val voice = reader.voice()
        var voiceFailed = false

        return ReplySession(
            scope = scope,
            phrases = phrases,
            autoSend = settings.autoSend,
            // Exclusive focus pauses music and navigation prompts while you talk, as the car mic docs ask.
            record = { stop, secondsLeft ->
                withFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) {
                    coroutineScope {
                        // You're not looking at the screen: beep when 10 s are left, so the cap doesn't cut you off unawares.
                        val warning = launch { secondsLeft.first { it <= 10 }; beep() }
                        recordUntilSilence(mic, stop, settings.silenceSeconds * 1000, secondsLeft).also { warning.cancel() }
                    }
                }
            },
            // No key means no speech to text: that ends in "send it as a voice message", not a lost recording.
            transcribe = { pcm -> (stt ?: error("No OpenRouter key")).transcribe(wav(pcm)) },
            cleanUp = rewriter?.let { r -> { text -> r.cleanUpDictation(text, detectLang(text) ?: lang) } },
            sendText = { tg.sendText(chatId, it) },
            sendVoice = { pcm ->
                val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.ogg") // TDLib uploads from here
                withContext(Dispatchers.IO) { encodeVoiceNote(pcm, file) }
                tg.sendVoice(chatId, file, pcm.size / (SAMPLE_RATE * 2))
            },
            speak = { text, textLang ->
                // ponytail: focus per sentence, so music can bob up between them; hold it per session if that's annoying.
                withFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                    val spoken = !voiceFailed && try {
                        voice.prepare(text, textLang).play()
                        true
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        voiceFailed = true
                        reader.phoneVoice.say(phrases.voiceFailed, lang)
                        false
                    }
                    if (!spoken) reader.phoneVoice.say(text, textLang)
                }
            },
        ).also { it.start() }
    }

    private suspend fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            delay(400)
        } finally {
            tone.release()
        }
    }

    private suspend fun <T> withFocus(gain: Int, block: suspend () -> T): T {
        val request = AudioFocusRequest.Builder(gain).setAudioAttributes(SPEECH_ATTRIBUTES).build()
        audio.requestAudioFocus(request)
        try {
            return block()
        } finally {
            audio.abandonAudioFocusRequest(request)
        }
    }
}
