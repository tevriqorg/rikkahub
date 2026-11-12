package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Unsubmitted edits are device-local drafts. Committed content uses the existing Message revision protocol. */
@Serializable
internal data class P14EditorSnapshot(
    val title: String,
    val body: String,
    val titleStart: Int = title.length,
    val titleEnd: Int = titleStart,
    val bodyStart: Int = body.length,
    val bodyEnd: Int = bodyStart,
    val focused: String = "body",
)

@Serializable
internal data class P14EntryEditDraft(
    val entryId: String,
    val sourceMessageId: String,
    val baseRevision: Int,
    val snapshot: P14EditorSnapshot,
    val undo: List<P14EditorSnapshot> = emptyList(),
    val redo: List<P14EditorSnapshot> = emptyList(),
    val scrollOffset: Int = 0,
) {
    val text: String get() = listOf(snapshot.title.trim(),snapshot.body.trimEnd()).filter { it.isNotBlank() }.joinToString("\n")
}

internal class P14EntryEditDraftStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("p14-entry-edit-drafts-v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun read(entryId: String): P14EntryEditDraft? = prefs.getString(entryId,null)?.let {
        runCatching { json.decodeFromString<P14EntryEditDraft>(it) }.getOrNull()
    }
    fun save(draft: P14EntryEditDraft) {
        check(prefs.edit().putString(draft.entryId,json.encodeToString(draft)).commit()) { "编辑草稿未写入，请重试" }
    }
    fun clear(entryId: String) { check(prefs.edit().remove(entryId).commit()) }
}
