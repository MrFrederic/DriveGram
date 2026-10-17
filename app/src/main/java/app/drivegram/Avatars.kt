package app.drivegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Round chat pictures for car rows and notifications: the chat's Telegram photo once it's downloaded, its initials
 * on a colour from Telegram's palette until then (or if it has none).
 */
object Avatars {
    // ponytail: by bytes, about 200 car-sized avatars; car rows fall back to the PNGs in cacheDir/avatars.
    private val cache = object : LruCache<String, Bitmap>(8 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val colors = longArrayOf(0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE)

    private fun photoKey(file: TdApi.File, size: Int) = "photo:${file.id}:$size"

    /** The photo if it's loaded, else the initials. Never blocks. */
    fun now(chat: TdApi.Chat, size: Int): Bitmap {
        chat.photo?.small?.let { file -> cache[photoKey(file, size)]?.let { return it } }
        val key = "initials:${chat.id}:${chat.title}:$size"
        return cache[key] ?: initials(chat, size).also { cache.put(key, it) }
    }

    /**
     * The avatar as a content URI for Android Auto, written to a cache file on first use. Car icons must be URIs, not
     * bitmaps: CarIcon treats all bitmaps as equal, so the host would never swap the initials for the photo. The
     * file name changes when the picture does, which is what makes the row count as changed.
     */
    fun uri(context: Context, chat: TdApi.Chat, size: Int): Uri {
        // The photo file, once written, outlives the memory cache: a long list evicts its own first photos from it.
        val photo = chat.photo?.small?.let { photoFile(context, it, size) }?.takeIf { it.exists() || !missing(chat, size) }
        val file = photo ?: File(context.cacheDir, "avatars/initials_${chat.id}_${carTitle(chat).hashCode()}_$size.png")
        if (!file.exists()) write(file, now(chat, size))
        return FileProvider.getUriForFile(context, "${context.packageName}.avatars", file)
    }

    private fun write(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        temp.renameTo(file)
    }

    private fun photoFile(context: Context, file: TdApi.File, size: Int) = File(context.cacheDir, "avatars/photo_${file.id}_$size.png")

    /** True if the chat has a photo that [load] hasn't fetched yet (in memory, or on disk when [context] is given). */
    fun missing(chat: TdApi.Chat, size: Int, context: Context? = null): Boolean {
        val file = chat.photo?.small ?: return false
        return cache[photoKey(file, size)] == null && context?.let { photoFile(it, file, size).exists() } != true
    }

    /**
     * Downloads (if needed) and decodes the chat's small photo. With [context] it also writes the PNG that [uri] serves,
     * right away: a long list loads more photos than the memory cache holds. Returns false if there's nothing to show.
     */
    suspend fun load(tg: ChatSource, chat: TdApi.Chat, size: Int, context: Context? = null): Boolean {
        val file = chat.photo?.small ?: return false
        val path = runCatching { tg.photoPath(file) }.onFailure { debugLog("download: ${it.message}") }.getOrNull()
        if (path == null) return false
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.let { round(it, size) } }
        if (bitmap == null) {
            debugLog("decode failed for a ${java.io.File(path).length()} byte file")
            return false
        }
        cache.put(photoKey(file, size), bitmap)
        if (context != null) withContext(Dispatchers.IO) { write(photoFile(context, file, size), bitmap) }
        return true
    }

    private fun debugLog(text: String) {
        if (BuildConfig.DEBUG) android.util.Log.w("DriveGram", "avatar $text")
    }

    private fun round(source: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun initials(chat: TdApi.Chat, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[((chat.id % colors.size + colors.size) % colors.size).toInt()].toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background)
        val letters = carTitle(chat).split(' ').filter { it.isNotBlank() }.take(2)
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }.joinToString("")
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.4f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect().also { text.getTextBounds(letters, 0, letters.length, it) }
        canvas.drawText(letters, size / 2f, size / 2f + bounds.height() / 2f, text)
        return out
    }
}
