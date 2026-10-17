package app.drivegram

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.speech.tts.TextToSpeech as AndroidTts

// Swappable engines: one interface per engine, one class per provider. The provider is picked in Settings.

/** Something ready to be spoken. Cancelling [play] stops it. */
fun interface Speech {
    suspend fun play()
}

/**
 * Text → speech. [prepare] does the slow part (network synthesis) so the next message can be prepared while
 * one plays. It plays instead of returning a raw stream because Android's engine can only play or write a file.
 */
interface TextToSpeech {
    suspend fun prepare(text: String, lang: String): Speech
}

/** Audio → text. Takes a WAV file's bytes; the language is detected by the model. */
interface SpeechToText {
    suspend fun transcribe(wav: ByteArray): String
}

interface TextRewriter {
    suspend fun prepareForSpeech(text: String, lang: String): String
    suspend fun cleanUpDictation(text: String, lang: String): String
}

/** Everything Play/Mic say goes to the car as speech, which ducks music and navigation. */
val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

class AndroidTextToSpeech(context: Context) : TextToSpeech {
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts = AndroidTts(context.applicationContext) { status ->
        if (status == AndroidTts.SUCCESS) ready.complete(Unit) else ready.completeExceptionally(IllegalStateException("TTS init failed: $status"))
    }

    init {
        tts.setAudioAttributes(SPEECH_ATTRIBUTES)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { pending.remove(id)?.complete(Unit) }
            override fun onStop(id: String, interrupted: Boolean) { pending.remove(id)?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { pending.remove(id)?.completeExceptionally(IllegalStateException("TTS error")) }
            override fun onError(id: String, errorCode: Int) { pending.remove(id)?.completeExceptionally(IllegalStateException("TTS error $errorCode")) }
        })
    }

    override suspend fun prepare(text: String, lang: String) = Speech { say(text, lang) }

    /** Writes [text] as a WAV file instead of playing it. Used to test speech to text without a microphone. */
    suspend fun synthesizeToFile(text: String, lang: String, file: java.io.File) {
        ready.await()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.language = Locale.forLanguageTag(lang)
        if (tts.synthesizeToFile(text, null, file, id) != AndroidTts.SUCCESS) {
            pending.remove(id)
            error("TTS rejected the text")
        }
        done.await()
    }

    suspend fun say(text: String, lang: String) {
        ready.await()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.language = Locale.forLanguageTag(lang)
        if (tts.speak(text, AndroidTts.QUEUE_ADD, null, id) != AndroidTts.SUCCESS) {
            pending.remove(id)
            error("TTS rejected the text")
        }
        try {
            done.await()
        } finally {
            if (!done.isCompleted) tts.stop() // cancelled mid-sentence
        }
    }
}

/**
 * Recent results, kept in memory. Keyed by the exact input text, so an edited message misses and its old entry ages
 * out. Entries go after [ttlMs], or oldest first once their [weight] adds up to more than [maxWeight].
 * ponytail: memory only, so it's gone when the app process dies; a file cache if replays across drives matter.
 */
class TimedCache<V : Any>(private val maxWeight: Long, private val ttlMs: Long, private val weight: (V) -> Int = { 1 }) {
    private class Entry<V>(val value: V, val time: Long)
    private val entries = LinkedHashMap<String, Entry<V>>(16, 0.75f, true) // access order: eldest = least recently used
    private var total = 0L

    @Synchronized fun get(key: String): V? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.time <= ttlMs) return entry.value
        remove(key)
        return null
    }

    @Synchronized fun put(key: String, value: V) {
        remove(key)
        entries[key] = Entry(value, System.currentTimeMillis())
        total += weight(value)
        while (total > maxWeight && entries.size > 1) remove(entries.keys.first())
    }

    private fun remove(key: String) {
        entries.remove(key)?.let { total -= weight(it.value) }
    }
}

/** How long tidied-up text and generated speech are reused, e.g. when you play the same chat again. */
private const val CACHE_TTL_MS = 60 * 60_000L

class OpenRouterException(val code: Int, message: String) : Exception("OpenRouter $code: $message")

/** A model from OpenRouter's catalog, as the settings pickers show it. */
data class ModelInfo(val id: String, val name: String, val price: String, val voices: List<String>)

/** What `GET /key` says about the key: null [limitRemaining] means no limit is set on it. */
data class KeyInfo(val label: String, val limitRemaining: Double?, val freeTier: Boolean)

/** Plain HTTP + org.json, both built into Android: a few endpoints don't need an HTTP or JSON library. */
class OpenRouter(private val apiKey: String?) {
    class Response(val body: ByteArray, val contentType: String?)

    suspend fun post(path: String, body: JSONObject): ByteArray = request("POST", path, body).body

    suspend fun postForAudio(path: String, body: JSONObject): Response = request("POST", path, body)

    private suspend fun get(path: String): JSONObject = JSONObject(String(request("GET", path, null).body))

    /**
     * Cancellable: a blocking socket read ignores coroutine cancellation, so a watcher disconnects the connection
     * when the caller is cancelled (Stop). Without it Stop would wait for the model to finish, up to the read timeout.
     */
    private suspend fun request(method: String, path: String, body: JSONObject?): Response = coroutineScope {
        val connection = URL("https://openrouter.ai/api/v1/$path").openConnection() as HttpURLConnection
        val watcher = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            withContext(Dispatchers.IO) { exchange(connection, method, body) }
        } catch (e: java.io.IOException) {
            ensureActive() // the socket error from our own disconnect is a cancellation, not a network failure
            throw e
        } finally {
            watcher.cancel()
        }
    }

    private fun exchange(connection: HttpURLConnection, method: String, body: JSONObject?): Response {
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            apiKey?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.setRequestProperty("X-Title", "DriveGram")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.use { String(it.readBytes()) }.orEmpty()
                val message = runCatching { JSONObject(error).getJSONObject("error").getString("message") }.getOrDefault("HTTP error")
                throw OpenRouterException(code, message)
            }
            return Response(connection.inputStream.use { it.readBytes() }, connection.contentType)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun chat(model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            // Thinking first stops small models second-guessing themselves in the answer ("Wait, I need to correct
            // that…"). Haiku 5.5 on OpenRouter returned no reasoning at low, medium or a forced budget; models that do
            // reason use it, models without reasoning ignore it.
            .put("reasoning", JSONObject().put("effort", "medium"))
            // Reasoning shares this budget. The cap also keeps OpenRouter's up-front cost reservation small, which
            // caused 402s without it.
            .put("max_tokens", 4000)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val response = JSONObject(String(post("chat/completions", body)))
        if (BuildConfig.DEBUG) {
            val usage = response.optJSONObject("usage")
            val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val reasoning = if (message.isNull("reasoning")) "" else message.optString("reasoning")
            // Lengths only: the reasoning quotes the message.
            android.util.Log.d("DriveGram", "chat $model: ${reasoning.length} characters of reasoning, ${usage?.optInt("completion_tokens")} completion tokens")
        }
        return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    suspend fun key(): KeyInfo {
        val data = get("key").getJSONObject("data")
        return KeyInfo(
            label = data.optString("label"),
            limitRemaining = if (data.isNull("limit_remaining")) null else data.getDouble("limit_remaining"),
            freeTier = data.optBoolean("is_free_tier"),
        )
    }

    /** Account balance in dollars: credits bought minus credits used. */
    suspend fun balance(): Double {
        val data = get("credits").getJSONObject("data")
        return data.getDouble("total_credits") - data.getDouble("total_usage")
    }

    /** Models that output [modality] ("text", "speech"). Public endpoint, no key needed. */
    suspend fun models(modality: String): List<ModelInfo> {
        val data = get(if (modality == "text") "models" else "models?output_modalities=$modality").getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it) }
            .filter { m -> m.optJSONObject("architecture")?.optJSONArray("output_modalities")?.let { a -> (0 until a.length()).any { a.getString(it) == modality } } ?: true }
            .map { m ->
                val voices = m.optJSONArray("supported_voices")
                ModelInfo(
                    id = m.getString("id"),
                    name = m.optString("name"),
                    price = price(m.optJSONObject("pricing"), unit = when (modality) { "speech" -> "characters"; "transcription" -> "seconds"; else -> null }),
                    voices = voices?.let { v -> (0 until v.length()).map { v.getString(it) } }.orEmpty(),
                )
            }
            .sortedBy { it.id }
    }

    /** "$0.10 per M tokens in · $0.50 per M tokens out". Speech input is billed per character, transcription per second. */
    private fun price(pricing: JSONObject?, unit: String?): String {
        pricing ?: return ""
        fun perMillion(key: String) = pricing.optString(key).toDoubleOrNull()?.takeIf { it > 0 }?.let { "$" + "%.2f".format(it * 1_000_000) }
        val input = if (unit == "seconds") {
            pricing.optString("prompt").toDoubleOrNull()?.takeIf { it > 0 }?.let { "$" + "%.2f".format(it * 3600) + " per hour" }
        } else {
            perMillion("prompt")?.let { "$it per M ${unit ?: "tokens in"}" }
        }
        val output = perMillion("completion")?.let { "$it per M tokens out" }
        return listOfNotNull(input, output).joinToString(" · ").ifEmpty { "free" }
    }
}

/**
 * OpenRouter's speech endpoint. Asks for MP3 (it carries its own sample rate); models that only do PCM (Gemini TTS)
 * answer 400, and then that model is remembered in [settings] and asked for PCM from then on.
 */
class OpenRouterTextToSpeech(
    private val api: OpenRouter,
    private val model: String,
    private val voice: String,
    private val settings: Settings,
) : TextToSpeech {
    /** [pcmRate] is null for MP3. */
    private class Audio(val bytes: ByteArray, val pcmRate: Int?)

    override suspend fun prepare(text: String, lang: String): Speech {
        val key = "$model|$voice|$text"
        val audio = cache.get(key) ?: synthesize(text).also { cache.put(key, it) }
        return Speech { if (audio.pcmRate == null) playMp3(audio.bytes) else playPcm(audio.bytes, audio.pcmRate) }
    }

    private suspend fun synthesize(text: String): Audio {
        if (!settings.ttsNeedsPcm(model)) {
            try {
                return Audio(synthesize(text, "mp3").body, null)
            } catch (e: OpenRouterException) {
                if (!isFormatError(e)) throw e
                settings.rememberTtsNeedsPcm(model)
            }
        }
        val pcm = synthesize(text, "pcm")
        // PCM has no header: take the rate from Content-Type ("audio/pcm;rate=24000") if the provider sends it.
        val rate = pcm.contentType?.let { Regex("rate=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: PCM_DEFAULT_RATE
        return Audio(pcm.body, rate)
    }

    private suspend fun synthesize(text: String, format: String) = api.postForAudio(
        "audio/speech",
        JSONObject().put("model", model).put("input", text).put("voice", voice).put("response_format", format),
    )

    private fun isFormatError(e: OpenRouterException) =
        e.code == 400 && listOf("format", "pcm", "mp3").any { e.message.orEmpty().contains(it, ignoreCase = true) }

    /** 16-bit mono PCM through AudioTrack. Returns when the last sample has played. */
    private suspend fun playPcm(pcm: ByteArray, rate: Int) = withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .build()
        try {
            track.play()
            var offset = 0
            while (offset < pcm.size) {
                ensureActive()
                val n = track.write(pcm, offset, minOf(8192, pcm.size - offset))
                if (n < 0) error("AudioTrack write failed: $n")
                offset += n
            }
            val frames = pcm.size / 2
            while (track.playbackHeadPosition < frames) {
                ensureActive()
                delay(20)
            }
        } finally {
            track.stop()
            track.release()
        }
    }

    private companion object {
        /** Gemini TTS's native output when the response doesn't say. */
        const val PCM_DEFAULT_RATE = 24_000
        /** 32 MB: about 10 minutes of 24 kHz PCM, hours of MP3. */
        val cache = TimedCache<Audio>(32L shl 20, CACHE_TTL_MS) { it.bytes.size }
    }

    private suspend fun playMp3(mp3: ByteArray) = suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        player.setAudioAttributes(SPEECH_ATTRIBUTES)
        player.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= mp3.size) return -1
                val n = minOf(size, mp3.size - position.toInt())
                System.arraycopy(mp3, position.toInt(), buffer, offset, n)
                return n
            }
            override fun getSize() = mp3.size.toLong()
            override fun close() {}
        })
        player.setOnCompletionListener { it.release(); if (cont.isActive) cont.resume(Unit) }
        player.setOnErrorListener { mp, what, extra ->
            mp.release()
            if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer error $what/$extra"))
            true
        }
        player.setOnPreparedListener { it.start() }
        cont.invokeOnCancellation { runCatching { player.stop(); player.release() } }
        player.prepareAsync()
    }
}

class OpenRouterSpeechToText(private val api: OpenRouter, private val model: String) : SpeechToText {
    override suspend fun transcribe(wav: ByteArray): String {
        val audio = JSONObject().put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)).put("format", "wav")
        val body = JSONObject().put("model", model).put("input_audio", audio) // no "language": you may speak either
        return JSONObject(String(api.post("audio/transcriptions", body))).optString("text")
    }
}

/** The message says only the lengths; [output] is for the settings test, which runs on our own sample text. */
class RewriteRejected(val output: String, inputLength: Int) :
    IllegalArgumentException("Rewrite rejected: got ${output.length} characters back for $inputLength")

/** [cached] false asks the model every time: the settings test runs the same sample again and again. */
class OpenRouterRewriter(private val api: OpenRouter, private val model: String, private val cached: Boolean = true) : TextRewriter {
    override suspend fun prepareForSpeech(text: String, lang: String): String {
        val key = "$model|$lang|$text"
        return readAloudCache.get(key)?.takeIf { cached } ?: rewrite(Prompts.READ_ALOUD, text, lang).also { readAloudCache.put(key, it) }
    }

    override suspend fun cleanUpDictation(text: String, lang: String) = rewrite(Prompts.CLEAN_UP_DICTATION, text, lang)

    private suspend fun rewrite(prompt: String, text: String, lang: String): String {
        val result = api.chat(model, "$prompt\n\n${Prompts.languageHint(lang)}", text)
        // A rewrite that's empty or balloons is the model answering or making things up, not tidying: don't use it.
        if (result.isBlank() || result.length > text.length * 2 + 80) throw RewriteRejected(result, text.length)
        return result
    }

    private companion object {
        // Dictation isn't cached: you never say the same thing twice on purpose.
        val readAloudCache = TimedCache<String>(maxWeight = 500, CACHE_TTL_MS)
    }
}
