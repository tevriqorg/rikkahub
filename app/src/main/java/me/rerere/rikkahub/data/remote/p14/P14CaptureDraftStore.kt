package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Persistent single-active Capture draft; never a submitted Entry, Outbox item or cloud sync. */
@Serializable
internal data class P14CaptureDraft(
    val draftId: String = UUID.randomUUID().toString(),
    val messageId: String = UUID.randomUUID().toString(),
    val text: String = "",
    val materials: List<P14DraftMaterial> = emptyList(),
)

@Serializable
internal data class P14DraftMaterial(
    val locator: String,
    val mime: String,
    val originalName: String,
    val scan: Boolean = false,
    val voiceDurationMs: Long? = null,
)

internal object P14DraftCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(draft: P14CaptureDraft): String = json.encodeToString(draft)
    fun decode(raw: String): P14CaptureDraft = json.decodeFromString(raw)
}

internal class P14CaptureDraftStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("p14-capture-draft-v1", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "p14_capture_draft").apply { mkdirs() }

    fun read(): P14CaptureDraft = prefs.getString("draft", null)?.let {
        runCatching { P14DraftCodec.decode(it) }.getOrNull()
    } ?: P14CaptureDraft()

    /** Used on each text mutation; commit before acknowledging the edited draft. */
    fun save(draft: P14CaptureDraft) {
        check(prefs.edit().putString("draft", P14DraftCodec.encode(draft)).commit()) {
            "Capture draft could not be persisted"
        }
    }

    fun import(source: Uri, scan: Boolean = false, voiceDurationMs: Long? = null): P14DraftMaterial {
        val mime = if (voiceDurationMs != null) "audio/mp4"
            else app.contentResolver.getType(source) ?: when {
                scan -> "image/jpeg"
                else -> "application/octet-stream"
            }
        val original = source.lastPathSegment?.substringAfterLast('/')?.take(150) ?: "material"
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
        val file = File(root, "${UUID.randomUUID()}${extension?.let { ".$it" }.orEmpty()}")
        try {
            app.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot access material source")
            check(file.isFile && file.length() > 0) { "Capture source is empty" }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return P14DraftMaterial(Uri.fromFile(file).toString(), mime, original, scan, voiceDurationMs)
    }

    /** Saving the accepted Entry moves ownership to durable P14 attachments; don't delete files. */
    fun clearAfterAccept() {
        check(prefs.edit().remove("draft").commit()) { "Draft clear failed" }
        root.listFiles()?.filter { it.isFile }?.forEach(File::delete)
    }
}
