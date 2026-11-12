package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.uuid.Uuid

// Hermes status posts are durable, but they are NOT final AI answers.
// This intentionally matches the P14 Server's precise progress wire format.
private val p14ProgressPattern = Regex("""^⏳ Working — \d+ min — iteration \d+/\d+(?:, .*)?$""")

internal fun isP14TransportProgress(text: String): Boolean =
    p14ProgressPattern.matches(text.trim()) ||
        text.trim().startsWith("💾 Self-improvement review:")

/**
 * Only show a recent progress marker, never fabricate a typing animation from
 * mutable Hermes drafts. The persisted Server final remains authoritative.
 */
internal fun recentP14Progress(
    messages: List<P14ProductMessageEntity>,
    nowMillis: Long,
): String? {
    val last = messages.asSequence()
        .filter { it.role == "assistant" && it.deliveryState == "SYNCED" && !it.tombstone }
        .maxWithOrNull(compareBy(P14ProductMessageEntity::createdAtEpochMs, P14ProductMessageEntity::messageId))
        ?: return null
    if (nowMillis - last.createdAtEpochMs !in 0..120_000L) return null
    return last.bodyText.trim().take(110).takeIf(p14ProgressPattern::matches)
}

/** Server message IDs may be non-UUID (h-...), so use stable name UUIDs. */
internal fun p14ServerAssistantUuid(messageId: String): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes(("p14:final:" + messageId).toByteArray(StandardCharsets.UTF_8)).toString())

/**
 * Reconcile only durable final replies of a local RikkaHub-owned P14 conversation.
 *
 * Native SSE can finish normally using its own random assistant ID. Match the
 * finalized text inside the relevant user turn before inserting the canonical
 * Server copy; late replay then stays idempotent by deterministic server ID.
 * Never replace RikkaHub's existing messages, branches, or selected model.
 */
internal fun mergeP14DurableFinals(
    existing: Conversation,
    serverMessages: List<P14ProductMessageEntity>,
): Conversation {
    val nativeUserIds = existing.messageNodes
        .flatMap { it.messages }
        .filter { it.role == MessageRole.USER }
        .mapTo(mutableSetOf()) { it.id.toString() }
    val ownedUsers = serverMessages.asSequence()
        .filter { it.role == "user" && it.messageId in nativeUserIds && !it.tombstone }
        .sortedWith(compareBy(P14ProductMessageEntity::createdAtEpochMs, P14ProductMessageEntity::messageId))
        .toList()
    if (ownedUsers.isEmpty()) return existing

    val nodes = existing.messageNodes.toMutableList()
    val finals = serverMessages.asSequence()
        .filter {
            it.role == "assistant" && it.deliveryState == "SYNCED" &&
                !it.tombstone && it.bodyText.isNotBlank() &&
                !isP14TransportProgress(it.bodyText)
        }
        .sortedWith(compareBy(P14ProductMessageEntity::createdAtEpochMs, P14ProductMessageEntity::messageId))
    for (final in finals) {
        val anchor = ownedUsers.lastOrNull { it.createdAtEpochMs <= final.createdAtEpochMs } ?: continue
        val anchorIndex = nodes.indexOfLast { node ->
            node.messages.any { it.role == MessageRole.USER && it.id.toString() == anchor.messageId }
        }
        if (anchorIndex < 0) continue
        val stableId = p14ServerAssistantUuid(final.messageId)
        if (nodes.any { node -> node.messages.any { it.id == stableId } }) continue
        val nextUserIndex = ((anchorIndex + 1) until nodes.size)
            .firstOrNull { index -> nodes[index].currentMessage.role == MessageRole.USER } ?: nodes.size
        val alreadyRenderedBySse = nodes.subList(anchorIndex + 1, nextUserIndex)
            .any { node ->
                node.messages.any {
                    it.role == MessageRole.ASSISTANT && it.toText().trim() == final.bodyText.trim()
                }
            }
        if (alreadyRenderedBySse) continue
        nodes.add(nextUserIndex, MessageNode.of(UIMessage(
            id = stableId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(final.bodyText)),
        )))
    }
    return if (nodes.size == existing.messageNodes.size) existing
    else existing.copy(messageNodes = nodes)
}
