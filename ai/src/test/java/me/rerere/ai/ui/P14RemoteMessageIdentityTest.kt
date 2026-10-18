package me.rerere.ai.ui

import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.uuid.Uuid

class P14RemoteMessageIdentityTest {
    @Test
    fun `p14 streaming finish adopts server message uuid`() {
        val expected = Uuid.parse("22222222-2222-4222-8222-222222222222")
        val user = UIMessage.user("hello")
        val handler = StreamChunkHandler(Model(modelId = "p14-hermes"))

        var messages = listOf(user)
        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "world"))
        val beforeFinish = messages.last().id
        messages = handler.handle(
            messages,
            StreamChunk.Finish(finishReason = "stop", responseId = expected.toString(), model = "p14-hermes"),
        )

        assertNotEquals(expected, beforeFinish)
        assertEquals(expected, messages.last().id)
    }

    @Test
    fun `non p14 response id never replaces local message identity`() {
        val responseId = "33333333-3333-4333-8333-333333333333"
        val user = UIMessage.user("hello")
        val handler = StreamChunkHandler(Model(modelId = "other-model"))

        var messages = listOf(user)
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "world"))
        val localId = messages.last().id
        messages = handler.handle(messages, StreamChunk.Finish(responseId = responseId))

        assertEquals(localId, messages.last().id)
    }
}
