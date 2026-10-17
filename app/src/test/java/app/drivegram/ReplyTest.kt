package app.drivegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTest {
    private val sentText = mutableListOf<String>()
    private val sentVoice = mutableListOf<ByteArray>()
    private val spoken = mutableListOf<String>()
    private var recordings = 0
    private var transcriptions = 0
    private val audio = byteArrayOf(1, 2, 3, 4)

    /** Unconfined: the fakes never suspend, so each call runs the flow to its next stop before returning. */
    private fun session(
        transcript: () -> String = { "um so I'll be late" },
        cleanUp: ((String) -> String)? = { "I'll be late." },
        heardSpeech: Boolean = true,
        autoSend: Boolean = false,
        sendFails: () -> Boolean = { false },
    ) = ReplySession(
        scope = CoroutineScope(Dispatchers.Unconfined),
        phrases = Phrases.En,
        autoSend = autoSend,
        record = { _, _ -> recordings++; Recorded(audio, heardSpeech) },
        transcribe = { transcriptions++; transcript() },
        cleanUp = cleanUp?.let { f -> { text: String -> f(text) } },
        sendText = { if (sendFails()) error("offline") else sentText += it },
        sendVoice = { if (sendFails()) error("offline") else sentVoice += it },
        speak = { text, _ -> spoken += text },
    ).also { it.start() }

    @Test fun `nothing is sent until you confirm`() {
        val reply = session()
        assertEquals(ReplyState.Confirm("I'll be late."), reply.state.value)
        assertTrue(sentText.isEmpty())
        assertEquals(listOf("I'll be late.", "Send?"), spoken)
        reply.send()
        assertEquals(listOf("I'll be late."), sentText)
        assertEquals(ReplyState.Sent, reply.state.value)
    }

    @Test fun `cancel at the confirm step sends nothing`() {
        val reply = session()
        reply.cancel()
        assertEquals(ReplyState.Cancelled, reply.state.value)
        reply.send()
        assertTrue(sentText.isEmpty() && sentVoice.isEmpty())
    }

    @Test fun `auto-send skips the confirm step`() {
        val reply = session(autoSend = true)
        assertEquals(listOf("I'll be late."), sentText)
        assertEquals(ReplyState.Sent, reply.state.value)
    }

    @Test fun `failed speech to text keeps the recording for a voice message`() {
        val reply = session(transcript = { error("STT down") })
        assertEquals(ReplyState.VoiceOnly, reply.state.value)
        assertEquals(Phrases.En.transcribeFailed, spoken.single())
        reply.send()
        assertArrayEquals(audio, sentVoice.single())
        assertTrue(sentText.isEmpty())
    }

    @Test fun `failed clean-up confirms the raw transcript and says so`() {
        val reply = session(cleanUp = { error("LLM down") })
        assertEquals(ReplyState.Confirm("um so I'll be late"), reply.state.value)
        assertEquals(Phrases.En.cleanUpFailed, spoken.first())
    }

    @Test fun `clean-up off confirms the transcript`() {
        assertEquals(ReplyState.Confirm("um so I'll be late"), session(cleanUp = null).state.value)
    }

    @Test fun `silence skips speech to text and can retry`() {
        val reply = session(heardSpeech = false)
        assertEquals(ReplyState.NothingHeard, reply.state.value)
        assertEquals(0, transcriptions)
        reply.retry()
        assertEquals(2, recordings)
    }

    @Test fun `blank transcript is nothing heard`() {
        assertEquals(ReplyState.NothingHeard, session(transcript = { "  " }).state.value)
    }

    @Test fun `failed send keeps the text and can try again`() {
        var fail = true
        val reply = session(sendFails = { fail })
        reply.send()
        assertEquals(ReplyState.SendFailed("I'll be late."), reply.state.value)
        fail = false
        reply.send()
        assertEquals(listOf("I'll be late."), sentText)
        assertEquals(ReplyState.Sent, reply.state.value)
    }

    @Test fun `failed voice send can try again with the same recording`() {
        var fail = true
        val reply = session(transcript = { error("STT down") }, sendFails = { fail })
        reply.send()
        assertEquals(ReplyState.SendFailed(null), reply.state.value)
        fail = false
        reply.send()
        assertArrayEquals(audio, sentVoice.single())
    }

    @Test fun `silence detector stops after a pause following speech`() {
        val detector = SilenceDetector(silenceToStopMs = 1800)
        var ms = 0
        fun feed(rms: Double, forMs: Int): SilenceDetector.Decision {
            var decision = SilenceDetector.Decision.CONTINUE
            repeat(forMs / 16) { decision = detector.feed(rms, 16); ms += 16 }
            return decision
        }
        assertEquals(SilenceDetector.Decision.CONTINUE, feed(100.0, 400)) // cabin noise, calibration
        assertEquals(SilenceDetector.Decision.CONTINUE, feed(2000.0, 1000)) // talking
        assertEquals(SilenceDetector.Decision.CONTINUE, feed(120.0, 1500)) // a short pause doesn't stop it
        assertEquals(SilenceDetector.Decision.STOP, feed(120.0, 400))
        assertTrue(detector.heardSpeech)
    }

    @Test fun `silence detector waits 5 s by default`() {
        val detector = SilenceDetector()
        repeat(25) { detector.feed(100.0, 16) } // calibration
        repeat(60) { detector.feed(2000.0, 16) } // talking
        val pause = (1..330).map { detector.feed(120.0, 16) } // 5.3 s of quiet
        assertEquals(SilenceDetector.Decision.CONTINUE, pause[290]) // still listening at 4.6 s
        assertEquals(SilenceDetector.Decision.STOP, pause.last())
    }

    @Test fun `silence detection off never stops on its own before the cap`() {
        val detector = SilenceDetector(silenceToStopMs = 0)
        val decisions = (1..1500).map { detector.feed(100.0, 16) } // 24 s, nobody talking
        assertTrue(decisions.all { it == SilenceDetector.Decision.CONTINUE })
        assertEquals(SilenceDetector.Decision.STOP, (1..2000).map { detector.feed(100.0, 16) }.last()) // 55 s cap
    }

    @Test fun `silence detector gives up when nobody talks`() {
        val detector = SilenceDetector()
        val decisions = (1..520).map { detector.feed(150.0, 16) } // 8.3 s of road noise
        assertEquals(SilenceDetector.Decision.STOP, decisions.last())
        assertEquals(false, detector.heardSpeech)
    }

    @Test fun `recording counts down the time left`() {
        var reads = 0
        val mic = object : MicSource {
            override fun start() {}
            override fun stop() {}
            override fun read(buffer: ByteArray, offset: Int, size: Int) = if (reads++ < 125) size else -1 // 2 s of 16 ms chunks, then the mic goes
        }
        val left = kotlinx.coroutines.flow.MutableStateFlow(MAX_RECORDING_MS / 1000)
        kotlinx.coroutines.runBlocking { recordUntilSilence(mic, kotlinx.coroutines.CompletableDeferred(), 0, left) }
        assertEquals(53, left.value)
    }

    @Test fun `wav header describes 16 kHz mono 16-bit`() {
        val file = wav(ByteArray(320))
        assertEquals(44 + 320, file.size)
        assertEquals("RIFF", String(file, 0, 4))
        assertEquals("WAVE", String(file, 8, 4))
        assertEquals(16_000, (file[24].toInt() and 0xff) or ((file[25].toInt() and 0xff) shl 8))
    }
}
