package app.drivegram

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

data class ApiKeys(val id: Int, val hash: String)

private val HASH = Regex("""\b[0-9a-fA-F]{32}\b""")
private val ID = Regex("""\b\d{4,10}\b""")

/**
 * Pulls api_id and api_hash out of whatever was copied from my.telegram.org: one value, both, or the whole page.
 * Either side is null if it isn't there. The hash is 32 hex characters; the id is the first standalone 4–10 digit number.
 */
fun parseApiKeys(text: String): Pair<String?, String?> {
    val hash = HASH.find(text)?.value
    val id = ID.findAll(text.replace(HASH, " ")).firstOrNull()?.value
    return id to hash?.lowercase()
}

fun validApiId(id: String) = id.toIntOrNull()?.let { it > 0 } == true
fun validApiHash(hash: String) = HASH.matches(hash)

@Composable
fun ApiKeysScreen(onSave: (ApiKeys) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var id by rememberSaveable { mutableStateOf("") }
    var hash by rememberSaveable { mutableStateOf("") }
    var pasteNote by rememberSaveable { mutableStateOf<String?>(null) }
    val idError = id.isNotEmpty() && !validApiId(id)
    val hashError = hash.isNotEmpty() && !validApiHash(hash)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect to Telegram", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Telegram asks every unofficial app to use its own API keys. DriveGram is open source and doesn't ship shared ones, " +
                "so you create a free pair for your account once. It takes about a minute, and the keys stay on this phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "1. Open my.telegram.org and log in with your phone number. The code arrives in your Telegram app.\n" +
                "2. Tap API development tools. Enter any app title and short name (for example DriveGram), pick Android, and tap Create application.\n" +
                "3. Copy api_id and api_hash, or the whole page, and tap Paste below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { uriHandler.openUri("https://my.telegram.org/apps") }, modifier = Modifier.fillMaxWidth()) {
            Text("Open my.telegram.org")
        }
        OutlinedButton(
            onClick = {
                val (pastedId, pastedHash) = parseApiKeys(clipboard.getText()?.text.orEmpty())
                pastedId?.let { id = it }
                pastedHash?.let { hash = it }
                pasteNote = when {
                    pastedId != null && pastedHash != null -> null
                    pastedId != null -> "Found api_id. Now copy api_hash and tap Paste again."
                    pastedHash != null -> "Found api_hash. Now copy api_id and tap Paste again."
                    else -> "Didn't find any keys in what you copied."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Paste") }
        pasteNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.trim() },
            label = { Text("api_id") },
            singleLine = true,
            isError = idError,
            supportingText = { Text(if (idError) "Numbers only" else "A number, like 1234567") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hash,
            onValueChange = { hash = it.trim() },
            label = { Text("api_hash") },
            singleLine = true,
            isError = hashError,
            supportingText = { Text(if (hashError) "Should be 32 letters and digits (0–9, a–f)" else "32 letters and digits") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = validApiId(id) && validApiHash(hash),
            onClick = { onSave(ApiKeys(id.toInt(), hash.lowercase())) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

/** Shown on the login screen when Telegram rejects the keys. */
@Composable
fun WrongApiKeys(onReset: () -> Unit) {
    TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Enter different API keys") }
}
