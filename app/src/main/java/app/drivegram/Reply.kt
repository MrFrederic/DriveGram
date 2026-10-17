package app.drivegram

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

// Mic: record → speech to text → clean-up → confirm → send. The flow (ReplySession) only sees lambdas, so it's
// tested with fakes; the Android parts (mic, encoding, engines) are wired up in Replies.

/** All recording is 16 kHz mono 16-bit PCM: what CarAudioRecord delivers, and the phone mic is set to match. */
const val SAMPLE_RATE = 16_000

/** Longest recording: speech to text times out at 60 s upstream. */
const val MAX_RECORDING_MS = 55_000

/** A microphone: the car's (CarAudioRecord) or the phone's. read() returns -1 when the source is gone. */
interface MicSource {
    fun start()
    fun read(buffer: ByteArray, offset: Int, size: Int): Int
    fun stop()
}

/** The phone's mic, for replying from the phone screen (and testing on an emulator). Needs RECORD_AUDIO. */
@SuppressLint("MissingPermission")
class PhoneMic : MicSource {
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096),
    )
    override fun start() = record.startRecording()
    override fun read(buffer: ByteArray, offset: Int, size: Int) = record.read(buffer, offset, size)
    override fun stop() {
        record.stop()
        record.release()
    }
}

sealed interface ReplyState {
    data object Recording : ReplyState
    data object Working : ReplyState
    data class Confirm(val text: String) : ReplyState
    data object NothingHeard : ReplyState
    /** Speech to text failed. The recording is kept so it can still go out as a voice message. */
    data object VoiceOnly : ReplyState
    data object Sending : ReplyState
    data object Sent : ReplyState
    /** [text] is null when it was the voice message that failed. */
    data class SendFailed(val text: String?) : ReplyState
    data object Cancelled : ReplyState
}

class Recorded(val pcm: ByteArray, val heardSpeech: Boolean)

/**
 * One reply, from the Mic tap to Sent or Cancelled. Nothing goes out without [send] unless [autoSend] is on, because
 * the LLM rewrites your words. [cleanUp] null means the clean-up step is off.
 */
class ReplySession(
    private val scope: CoroutineScope,
    private val phrases: Phrases,
    private val autoSend: Boolean,
    /** Stops when [stop] completes; counts [secondsLeft] down to the time cap as it goes. */
    private val record: suspend (stop: CompletableDeferred<Unit>, secondsLeft: MutableStateFlow<Int>) -> Recorded,
    private val transcribe: suspend (ByteArray) -> String,
    private val cleanUp: (suspend (String) -> String)?,
    private val sendText: suspend (String) -> Unit,
    private val sendVoice: suspend (ByteArray) -> Unit,
    private val speak: suspend (text: String, lang: String) -> Unit,
) {
    private val _state = MutableStateFlow<ReplyState>(ReplyState.Recording)
    val state: StateFlow<ReplyState> = _state
    private val _secondsLeft = MutableStateFlow(MAX_RECORDING_MS / 1000)
    /** Recording time left, shown while [ReplyState.Recording]. */
    val secondsLeft: StateFlow<Int> = _secondsLeft

    private var stopRecording = CompletableDeferred<Unit>()
    private var job: Job? = null
    private var speech: Job? = null
    private var recording: ByteArray? = null

    fun start() {
        stopRecording = CompletableDeferred()
        _secondsLeft.value = MAX_RECORDING_MS / 1000
        _state.value = ReplyState.Recording
        job = scope.launch { recordAndTranscribe() }
    }

    /** Done tapped: stop listening and carry on with what was recorded. */
    fun stopRecording() {
        stopRecording.complete(Unit)
    }

    fun send() {
        val state = _state.value
        when {
            state is ReplyState.Confirm -> deliver(state.text)
            state is ReplyState.SendFailed && state.text != null -> deliver(state.text)
            state is ReplyState.VoiceOnly || state is ReplyState.SendFailed -> deliverVoice()
        }
    }

    /** Record again after "didn't catch that". */
    fun retry() {
        if (_state.value == ReplyState.NothingHeard) start()
    }

    fun cancel() {
        stopRecording.complete(Unit)
        job?.cancel()
        speech?.cancel()
        recording = null
        _state.value = ReplyState.Cancelled
    }

    private suspend fun recordAndTranscribe() {
        val recorded = record(stopRecording, _secondsLeft)
        if (_state.value == ReplyState.Cancelled) return
        if (!recorded.heardSpeech || recorded.pcm.isEmpty()) {
            say(ReplyState.NothingHeard, phrases.nothingHeard)
            return
        }
        recording = recorded.pcm
        _state.value = ReplyState.Working
        val transcript = try {
            transcribe(recorded.pcm).trim()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            say(ReplyState.VoiceOnly, phrases.transcribeFailed)
            return
        }
        if (transcript.isEmpty()) {
            say(ReplyState.NothingHeard, phrases.nothingHeard)
            return
        }
        var notice: String? = null
        val text = cleanUp?.let {
            try {
                it(transcript).trim().ifEmpty { transcript }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice = phrases.cleanUpFailed
                transcript
            }
        } ?: transcript
        if (autoSend) {
            notice?.let { speak(it, phrases.lang) }
            deliver(text)
        } else {
            _state.value = ReplyState.Confirm(text)
            speech = scope.launch {
                notice?.let { speak(it, phrases.lang) }
                speak(text, detectLang(text) ?: phrases.lang)
                speak(phrases.sendQuestion, phrases.lang)
            }
        }
    }

    private fun deliver(text: String) = sendWith(ReplyState.SendFailed(text)) { sendText(text) }

    private fun deliverVoice() {
        val pcm = recording ?: return
        sendWith(ReplyState.SendFailed(null)) { sendVoice(pcm) }
    }

    private fun sendWith(failed: ReplyState, action: suspend () -> Unit) {
        speech?.cancel()
        _state.value = ReplyState.Sending
        job = scope.launch {
            try {
                action()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                say(failed, phrases.sendFailed)
                return@launch
            }
            recording = null
            say(ReplyState.Sent, phrases.sent)
        }
    }

    private fun say(state: ReplyState, text: String) {
        _state.value = state
        speech?.cancel()
        speech = scope.launch { speak(text, phrases.lang) }
    }
}

/**
 * Decides when to stop listening, from the loudness of each chunk. It calibrates to the cabin's noise in the first
 * [calibrationMs], then counts anything [speechFactor] times louder (and above [minSpeechRms]) as speech.
 * ponytail: plain RMS threshold. These are the knobs to tune in a real car; a VAD model if they're not enough.
 */
class SilenceDetector(
    /** 0 turns silence detection off: only Done (or the time cap) stops the recording. */
    private val silenceToStopMs: Int = 5000,
    private val noSpeechTimeoutMs: Int = maxOf(8000, silenceToStopMs + 3000),
    private val maxMs: Int = MAX_RECORDING_MS,
    private val calibrationMs: Int = 300,
    private val speechFactor: Double = 2.5,
    private val minSpeechRms: Double = 300.0,
) {
    enum class Decision { CONTINUE, STOP }

    var heardSpeech = false
        private set
    private var elapsed = 0
    private var silentFor = 0
    private var noiseSum = 0.0
    private var noiseChunks = 0

    fun feed(rms: Double, chunkMs: Int): Decision {
        elapsed += chunkMs
        if (elapsed <= calibrationMs) {
            noiseSum += rms
            noiseChunks++
        }
        val noise = if (noiseChunks == 0) 0.0 else noiseSum / noiseChunks
        val speech = elapsed > calibrationMs && rms > maxOf(noise * speechFactor, minSpeechRms)
        if (speech) {
            heardSpeech = true
            silentFor = 0
        } else {
            silentFor += chunkMs
        }
        return when {
            elapsed >= maxMs -> Decision.STOP
            silenceToStopMs == 0 -> Decision.CONTINUE
            heardSpeech && silentFor >= silenceToStopMs -> Decision.STOP
            !heardSpeech && elapsed >= noSpeechTimeoutMs -> Decision.STOP
            else -> Decision.CONTINUE
        }
    }
}

/** Root mean square of 16-bit little-endian samples: a plain loudness measure. */
fun rms(pcm: ByteArray, length: Int): Double {
    var sum = 0.0
    var i = 0
    while (i + 1 < length) {
        val sample = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
        sum += sample.toDouble() * sample
        i += 2
    }
    val samples = length / 2
    return if (samples == 0) 0.0 else sqrt(sum / samples)
}

/** Reads [mic] until silence, [stop], or the time cap. Runs on IO; the mic is stopped whatever happens. */
suspend fun recordUntilSilence(
    mic: MicSource,
    stop: CompletableDeferred<Unit>,
    silenceMs: Int,
    secondsLeft: MutableStateFlow<Int>? = null,
): Recorded = withContext(Dispatchers.IO) {
    val detector = SilenceDetector(silenceToStopMs = silenceMs)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(512) // CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE: 16 ms of audio
    var stoppedByHand = false
    mic.start()
    try {
        while (isActive) {
            if (stop.isCompleted) {
                stoppedByHand = true
                break
            }
            val n = mic.read(buffer, 0, buffer.size)
            if (n < 0) break // the car's mic was dismissed: keep what we have
            if (n == 0) continue
            out.write(buffer, 0, n)
            secondsLeft?.value = (MAX_RECORDING_MS - out.size() / BYTES_PER_MS + 999) / 1000
            if (detector.feed(rms(buffer, n), n / 32) == SilenceDetector.Decision.STOP) break
        }
    } finally {
        mic.stop()
    }
    // Tapping Done always counts: never throw away something you chose to send because it seemed quiet.
    Recorded(out.toByteArray(), heardSpeech = detector.heardSpeech || (stoppedByHand && out.size() > 0))
}

private const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1000

/** 16 kHz mono 16-bit PCM wrapped in a WAV header, for speech to text. */
fun wav(pcm: ByteArray): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
        putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size)
    }
    return header.array() + pcm
}

/** Encodes PCM as OGG/Opus, the format Telegram plays as a voice message. Android 10+ (MediaMuxer OGG). */
fun encodeVoiceNote(pcm: ByteArray, out: File) {
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val info = MediaCodec.BufferInfo()
    var track = -1
    var inputPos = 0
    var inputDone = false
    try {
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val n = minOf(buffer.remaining(), pcm.size - inputPos)
                    buffer.put(pcm, inputPos, n)
                    val timeUs = inputPos / 2 * 1_000_000L / SAMPLE_RATE
                    inputPos += n
                    inputDone = inputPos >= pcm.size
                    codec.queueInputBuffer(index, 0, n, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    } finally {
        codec.stop()
        codec.release()
        if (track >= 0) muxer.stop()
        muxer.release()
    }
}
