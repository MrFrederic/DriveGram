package app.drivegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Messages Play reads when nothing is unread. */
    var lastN: Int
        get() = prefs.getInt("last_n", 3)
        set(value) = prefs.edit().putInt("last_n", value).apply()

    /** Run messages through the LLM before speaking. Needs an OpenRouter key. */
    var readAloudLlm: Boolean
        get() = prefs.getBoolean("read_aloud_llm", true)
        set(value) = prefs.edit().putBoolean("read_aloud_llm", value).apply()

    var ttsEngine: TtsEngine
        get() = TtsEngine.valueOf(prefs.getString("tts_engine", TtsEngine.ANDROID.name)!!)
        set(value) = prefs.edit().putString("tts_engine", value.name).apply()

    var llmModel: String
        get() = prefs.getString("llm_model", null)?.trim().orEmpty().ifEmpty { DEFAULT_LLM_MODEL }
        set(value) = prefs.edit().putString("llm_model", value).apply()

    var ttsModel: String
        get() = prefs.getString("tts_model", null)?.trim().orEmpty().ifEmpty { DEFAULT_TTS_MODEL }
        set(value) = prefs.edit().putString("tts_model", value).apply()

    var ttsVoice: String
        get() = prefs.getString("tts_voice", null)?.trim().orEmpty().ifEmpty { DEFAULT_TTS_VOICE }
        set(value) = prefs.edit().putString("tts_voice", value).apply()

    var sttModel: String
        get() = prefs.getString("stt_model", null)?.trim().orEmpty().ifEmpty { DEFAULT_STT_MODEL }
        set(value) = prefs.edit().putString("stt_model", value).apply()

    /** Run your dictation through the LLM before confirming. */
    var cleanUpDictation: Boolean
        get() = prefs.getBoolean("clean_up_dictation", true)
        set(value) = prefs.edit().putBoolean("clean_up_dictation", value).apply()

    /** Send replies without the Send/Cancel step. Off unless you turn it on: the LLM rewrites your words. */
    var autoSend: Boolean
        get() = prefs.getBoolean("auto_send", false)
        set(value) = prefs.edit().putBoolean("auto_send", value).apply()

    /** Voice models found to reject MP3 (Gemini TTS only does PCM). Learned on the first failed request. */
    fun ttsNeedsPcm(model: String) = model in prefs.getStringSet("tts_pcm_models", emptySet())!!

    fun rememberTtsNeedsPcm(model: String) =
        prefs.edit().putStringSet("tts_pcm_models", prefs.getStringSet("tts_pcm_models", emptySet())!! + model).apply()

    /** Seconds of silence that end a recording; 0 means only Done ends it. */
    var silenceSeconds: Int
        get() = prefs.getInt("silence_seconds", 5)
        set(value) = prefs.edit().putInt("silence_seconds", value).apply()

    /** Set once you've seen the notice that message text and your voice go to OpenRouter. */
    var privacyNoticeSeen: Boolean
        get() = prefs.getBoolean("privacy_notice_seen", false)
        set(value) = prefs.edit().putBoolean("privacy_notice_seen", value).apply()

    /** Stored encrypted with an AES key that never leaves the Android Keystore. */
    var openRouterKey: String?
        get() = prefs.getString(OPENROUTER_KEY, null)?.let { stored ->
            runCatching { decrypt(stored) }
                .onFailure { if (BuildConfig.DEBUG) android.util.Log.w("DriveGram", "OpenRouter key decrypt failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
        set(value) {
            prefs.edit().apply { if (value.isNullOrBlank()) remove(OPENROUTER_KEY) else putString(OPENROUTER_KEY, encrypt(value.trim())) }.apply()
        }

    /** Telegram api_id/api_hash: entered in the app, or from local.properties in your own builds. Hash stored encrypted. */
    var telegramApi: ApiKeys?
        get() {
            val id = prefs.getInt(TG_API_ID, 0)
            val hash = prefs.getString(TG_API_HASH, null)?.let { runCatching { decrypt(it) }.getOrNull() }
            return if (id != 0 && hash != null) ApiKeys(id, hash)
            else ApiKeys(BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH).takeIf { it.id != 0 }
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(TG_API_ID).remove(TG_API_HASH) else putInt(TG_API_ID, value.id).putString(TG_API_HASH, encrypt(value.hash))
            }.apply()
        }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes, 0, GCM_IV_BYTES))
        }
        return String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES))
    }

    enum class TtsEngine { ANDROID, OPENROUTER }

    companion object {
        const val DEFAULT_LLM_MODEL = "google/gemini-3.7-flash" // reasons on OpenRouter; Haiku 5.5 didn't and second-guessed itself in the answer
        const val DEFAULT_TTS_MODEL = "google/gemini-3.8-flash-lite-tts"
        const val DEFAULT_TTS_VOICE = "Kore"
        const val DEFAULT_STT_MODEL = "elevenlabs/scribe-v2"
        private const val OPENROUTER_KEY = "openrouter_key"
        private const val TG_API_ID = "tg_api_id"
        private const val TG_API_HASH = "tg_api_hash"
        private const val KEY_ALIAS = "drivegram_settings"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
    }
}
