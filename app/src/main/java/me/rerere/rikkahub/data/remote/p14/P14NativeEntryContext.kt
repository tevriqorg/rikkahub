package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.uuid.Uuid

private const val SOURCE_PREFIX = "[P14_ENTRY_CONTEXT:"
private const val SOURCE_END = "[/P14_ENTRY_CONTEXT]"

/**
 * Entry is data, not an AI turn. Preparing a native RikkaHub conversation does not
 * invoke any provider, P14 chat endpoint, or Hermes session.
 */
internal fun nativeChatIdForEntry(entryId: String): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes(
        "p14-native-entry-chat:$entryId".toByteArray(StandardCharsets.UTF_8),
    ).toString())

internal fun entryContextMessage(entryId: String, text: String): String =
    "$SOURCE_PREFIX$entryId]\n以下是已保存的 Entry 原文，只作为背景资料，不是本次提问。请根据后续问题作答。\n$text\n$SOURCE_END"

/**
 * Reuse the same native chat for each Entry; a revised Entry updates its one
 * source node but never replaces real user turns or assistant replies.
 */
internal suspend fun prepareNativeEntryChat(
    entryId: String,
    sourceText: String,
    conversations: ConversationRepository,
    settingsStore: SettingsStore,
): Uuid {
    require(entryId.isNotBlank()) { "Entry identity is required" }
    val id = nativeChatIdForEntry(entryId)
    val source = entryContextMessage(entryId, sourceText)
    val existing = conversations.getConversationById(id)
    if (existing == null) {
        val assistantId = settingsStore.awaitLoaded().getCurrentAssistant().id
        conversations.insertConversation(
            Conversation.ofId(id, assistantId).copy(
                title = sourceText.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.trim()?.take(70) ?: "Entry 讨论",
                messageNodes = listOf(MessageNode.of(UIMessage.user(source))),
            ),
        )
    } else {
        val sourceNode = existing.messageNodes.firstOrNull()
        val currentSource = sourceNode?.messages?.getOrNull(sourceNode.selectIndex)
        if (currentSource?.role == MessageRole.USER &&
            currentSource.toText().startsWith("$SOURCE_PREFIX$entryId]") &&
            currentSource.toText() != source
        ) {
            val originalNode = requireNotNull(sourceNode)
            val updatedMessages = originalNode.messages.toMutableList()
            updatedMessages[sourceNode.selectIndex] =
                currentSource.copy(parts = listOf(UIMessagePart.Text(source)))
            conversations.updateConversation(
                existing.copy(messageNodes = listOf(
                    originalNode.copy(messages = updatedMessages),
                ) + existing.messageNodes.drop(1)),
            )
        }
    }
    return id
}

/**
 * Display Entry source as a compact context card rather than a user question.
 * The complete original content remains in the native chat history for providers.
 */
internal fun nativeEntrySourceOrNull(message: UIMessage): String? {
    val text = message.toText()
    if (!text.startsWith(SOURCE_PREFIX) || !text.contains(SOURCE_END)) return null
    return text.substringAfter('\n', "").substringAfter('\n', "")
        .substringBefore("\n$SOURCE_END")
}

/**
 * The P14 OpenAI facade consumes only the most recent user message, not the
 * native conversation's message history. Attach current Entry source to each
 * outgoing remote turn so even an edited Entry is reflected immediately.
 * Do not mutate locally saved messages. Normal providers use ordinary history.
 */
internal fun withEntryContextForFirstRemoteTurn(messages: List<UIMessage>): List<UIMessage> {
    val context = messages.firstOrNull {
        it.role == MessageRole.USER && it.toText().startsWith(SOURCE_PREFIX) &&
            it.toText().contains(SOURCE_END)
    }?.toText() ?: return messages
    val last = messages.indexOfLast { it.role == MessageRole.USER }
    if (last <= 0) return messages
    val selected = messages[last]
    return messages.toMutableList().apply {
        set(last, selected.copy(parts = listOf(
            UIMessagePart.Text("$context\n\n用户当前的问题：\n"),
        ) + selected.parts))
    }
}
