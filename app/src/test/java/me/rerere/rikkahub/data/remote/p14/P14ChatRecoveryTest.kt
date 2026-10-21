package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class P14ChatRecoveryTest {
    private val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val assistantId = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val acceptedId = Uuid.parse("33333333-3333-4333-8333-333333333333")
    private val anotherId = Uuid.parse("44444444-4444-4444-8444-444444444444")
    private fun accepted(id: Uuid) = UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("accepted")))

    @Test fun repeatedRecoveryIsIdempotentAndDoesNotDuplicateAcceptedIds() {
        val existing = Conversation.ofId(conversationId, assistantId)
        val first = mergeAcceptedP14Messages(existing, listOf(accepted(acceptedId)))
        assertEquals(listOf(acceptedId), first.currentMessages.map { it.id })
        assertSame(first, mergeAcceptedP14Messages(first, listOf(accepted(acceptedId))))
    }

    @Test fun repairOnlyAppendsMissingAcceptedMessagesWithoutClobberingAssistantOrBranches() {
        val assistant = UIMessage(id = anotherId, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("existing result")))
        val current = Conversation.ofId(conversationId, assistantId, messages = listOf(MessageNode.of(assistant)))
        val repaired = mergeAcceptedP14Messages(current, listOf(accepted(acceptedId), accepted(acceptedId)))
        assertEquals(2, repaired.messageNodes.size)
        assertEquals(anotherId, repaired.messageNodes.first().currentMessage.id)
        assertEquals(acceptedId, repaired.messageNodes.last().currentMessage.id)
        assertEquals(current.assistantId, repaired.assistantId)
    }
}
