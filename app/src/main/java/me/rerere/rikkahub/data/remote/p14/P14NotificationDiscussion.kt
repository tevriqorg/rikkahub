package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.AssistantRuntimeMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.uuid.Uuid

private val NOTIFICATION_ID = Regex("^ntf-[0-9a-f]{32}$")

/** Separate notification discussion from Entry. This does not start an Agent. */
internal fun nativeChatIdForNotification(id: String): Uuid {
    require(NOTIFICATION_ID.matches(id)) { "Invalid P14 notification ID" }
    return nativeChatIdForEntry("notification:$id")
}

/** Preview only; Hermes re-resolves this stable ID against authoritative P14 storage. */
internal fun notificationDiscussionSource(notification: P14NotificationDto): String {
    val id = notification.notificationId
    require(NOTIFICATION_ID.matches(id))
    return entryContextMessage(
        entryId = "notification:$id",
        text = buildString {
            appendLine("来自 P14 通知中心的上下文引用")
            appendLine("通知：${notification.title}")
            appendLine("内容：${notification.body}")
            appendLine("来源：${notification.producerId}")
            appendLine("通知 ID：$id")
            appendLine("状态：${notification.state}")
            append("本地仅显示摘要。远端 Agent 必须重新读取 P14 Server 的正式通知和关联资料；通知本身不是执行授权。")
        },
    )
}

internal fun notificationDiscussionSourceMessageId(id: String): Uuid {
    require(NOTIFICATION_ID.matches(id))
    return Uuid.parse(UUID.nameUUIDFromBytes(
        "p14-notification-source:$id".toByteArray(StandardCharsets.UTF_8)
    ).toString())
}

/**
 * Explicitly create one native conversation per Notification. The source is
 * persisted through P14 Capture/Outbox with defer_ai before the native chat can
 * send a user turn, so the server can bind the notification by stable ID.
 */
internal suspend fun prepareNativeNotificationDiscussion(
    notification: P14NotificationDto,
    product: P14ProductRepository,
    conversations: ConversationRepository,
    settingsStore: SettingsStore,
): Uuid {
    val id = nativeChatIdForNotification(notification.notificationId)
    val settings = settingsStore.awaitLoaded()
    val chosen = settings.getCurrentAssistant()
    val assistant = (
        listOf(chosen) + settings.assistants.filterNot { it.id == chosen.id }
    ).firstOrNull {
        settings.findModelById(it.chatModelId ?: settings.chatModelId)?.modelId == "p14-hermes"
    } ?: error("请先在 RikApp 配置 p14-hermes 远程模型")
    val model = requireNotNull(settings.findModelById(assistant.chatModelId ?: settings.chatModelId)) {
        "p14-hermes 模型设置不可用"
    }
    val source = notificationDiscussionSource(notification)
    val sourceMessage = UIMessage(
        id = notificationDiscussionSourceMessageId(notification.notificationId),
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(source)),
    )
    // Persist before inserting the native conversation. A crash or repeated
    // tap is idempotent because source and chat UUIDs are deterministic.
    val accepted = product.acceptRikkaMessage(
        externalSessionId = id.toString(),
        localConversationId = id.toString(),
        message = sourceMessage,
        assistantId = assistant.id.toString(),
        modelId = model.id.toString(),
        deferAi = true,
    )
    // A first real native question can otherwise race past the deferred
    // Capture before it is present on P14 Server. Do not open a conversation
    // whose stable notification context has not been durably delivered.
    check(product.syncAndFlushActive() &&
        product.pendingChatDelivery(accepted.conversationId)==null) {
        "通知上下文尚未同步成功，请联网后重试"
    }
    if (conversations.getConversationById(id) == null) {
        conversations.insertConversation(
            Conversation.ofId(id, assistant.id).copy(
                title = "通知 · ${notification.title}".take(70),
                messageNodes = listOf(MessageNode.of(sourceMessage)),
                config = ConversationConfig(
                    chatModelId = model.id,
                    runtimeMode = AssistantRuntimeMode.REMOTE,
                ),
            ),
        )
    }
    return id
}
