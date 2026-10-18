package me.rerere.rikkahub.data.remote.p14

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.AssistantRuntimeMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/**
 * P0: Rebuild the ordinary RikkaHub chat projection from durable P14 local acceptance.
 * The P14 tables stay authoritative for accepted operations. This never sends network traffic,
 * never resubmits a turn, and never projects a remote-only second-device conversation.
 */
internal class P14ChatRecovery(
    private val dao: P14ProductDao,
    private val conversations: ConversationRepository,
    private val settingsStore: SettingsStore,
) {
    companion object { private val mutex = Mutex() }

    suspend fun restoreAll(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = settingsStore.awaitLoaded()
            dao.localConversations().count { restore(it, settings) }
        }
    }

    suspend fun restoreOne(localId: Uuid): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = dao.conversationByLocalId(localId.toString()) ?: return@withLock false
            restore(source, settingsStore.awaitLoaded())
        }
    }

    private suspend fun restore(source: P14ProductConversationEntity, settings: Settings): Boolean {
        val localId = source.localConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return false
        val existing = conversations.getConversationById(localId)
        val modelId = source.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val storedModel = settings.findModelById(modelId)
        val matchingAssistants = settings.assistants.filter { assistant ->
            settings.findModelById(assistant.chatModelId ?: settings.chatModelId)?.modelId == "p14-hermes"
        }
        val assistant = source.assistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let(settings::getAssistantById)
            ?: existing?.let { settings.getAssistantById(it.assistantId) }
            ?: matchingAssistants.singleOrNull()
            ?: return false  // No safe local owner. Never invent a non-P14 chat.
        val model = storedModel
            ?: existing?.let(settings::getChatModelOf)
            ?: settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        if (model?.modelId != "p14-hermes") return false
        if (existing != null && existing.assistantId != assistant.id) return false

        // The local-first P0 repair only projects accepted user mutations. It must not duplicate
        // assistant streaming replies whose RikkaHub IDs may differ from server message IDs.
        val recovered = dao.listMessages(source.conversationId)
            .filter { it.role == "user" && !it.tombstone }
            .mapNotNull { row ->
                val uuid = runCatching { Uuid.parse(row.messageId) }.getOrNull() ?: return@mapNotNull null
                val parts = buildList<UIMessagePart> {
                    if (row.bodyText.isNotBlank()) add(UIMessagePart.Text(row.bodyText))
                    dao.attachmentsForMessage(row.messageId).forEach { attachment ->
                        val locator = attachment.localLocator.takeIf { it.isNotBlank() } ?: return@forEach
                        add(recoverP14AttachmentPart(attachment) ?: return@forEach)
                    }
                }
                if (parts.isEmpty()) null else UIMessage(uuid, MessageRole.USER, parts)
            }
        if (recovered.isEmpty()) return false
        if (existing == null) {
            val entry = dao.entry(source.entryId)
            val created = Conversation.ofId(localId, assistant.id).copy(
                title = entry?.bodyText?.take(70)?.takeIf { it.isNotBlank() } ?: "P14 recovered Entry",
                messageNodes = recovered.map { MessageNode.of(it) },
                config = ConversationConfig(chatModelId = model.id, runtimeMode = AssistantRuntimeMode.REMOTE),
            )
            // A racing restart/foreground path can have saved it first; never overwrite that path.
            if (conversations.getConversationById(localId) == null) {
                conversations.insertConversation(created)
                return true
            }
        }
        val current = conversations.getConversationById(localId) ?: return false
        val merged = mergeAcceptedP14Messages(current, recovered)
        if (merged == current) return false
        conversations.updateConversation(merged)
        return true
    }
}

/** Idempotent append-only repair: preserve existing nodes, branching, and model output. */
internal fun mergeAcceptedP14Messages(existing: Conversation, accepted: List<UIMessage>): Conversation {
    val ids = existing.messageNodes.flatMap { it.messages }.mapTo(mutableSetOf()) { it.id }
    val missing = accepted.filter { ids.add(it.id) }
    if (missing.isEmpty()) return existing
    return existing.copy(messageNodes = existing.messageNodes + missing.map(MessageNode::of))
}

/** Recover True Voice as VOICE rather than silently downgrading it to imported AUDIO. */
internal fun recoverP14AttachmentPart(attachment: P14ProductAttachmentEntity): UIMessagePart? {
    val locator = attachment.localLocator.takeIf { it.isNotBlank() } ?: return null
    return when (attachment.kind) {
        "IMAGE" -> UIMessagePart.Image(locator)
        "VIDEO" -> UIMessagePart.Video(locator)
        "VOICE" -> UIMessagePart.Audio(locator, buildJsonObject {
            put("p14_media_role", "voice_message")
            put("p14_mime_type", attachment.mimeType)
            attachment.durationMs?.let { put("p14_duration_ms", it) }
        })
        "AUDIO" -> UIMessagePart.Audio(locator)
        else -> UIMessagePart.Document(locator, attachment.fileName, attachment.mimeType)
    }
}
