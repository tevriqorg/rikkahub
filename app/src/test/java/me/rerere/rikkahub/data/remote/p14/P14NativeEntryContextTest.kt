package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P14NativeEntryContextTest {
    @Test fun entryIdsUseStableSeparateNativeConversationIdentities() {
        val first = nativeChatIdForEntry("entry-abc")
        assertEquals(first, nativeChatIdForEntry("entry-abc"))
        assertNotEquals(first, nativeChatIdForEntry("entry-def"))
    }

    @Test fun newEntryIsContextOnlyAndDoesNotGenerateAnAssistantReply() {
        val source = UIMessage.user(entryContextMessage("entry-abc", "长野之旅\n随时可能下雨"))
        val result = withEntryContextForFirstRemoteTurn(listOf(source))
        assertEquals(listOf(source), result)
        assertTrue(result.none { it.role.name == "ASSISTANT" })
    }

    @Test fun firstRemoteQuestionReceivesContextWithoutMutatingLocalHistory() {
        val source = UIMessage.user(entryContextMessage("entry-abc", "原始长野行程"))
        val question = UIMessage.user("如果下雨怎么办？")
        val local = listOf(source, question)
        val outgoing = withEntryContextForFirstRemoteTurn(local)
        assertEquals(local, listOf(source, question))
        assertEquals(2, outgoing.size)
        assertEquals(source, outgoing.first())
        assertTrue(outgoing.last().toText().contains("原始长野行程"))
        assertTrue(outgoing.last().toText().contains("如果下雨怎么办？"))
        assertFalse(question.toText().contains("原始长野行程"))
        assertEquals(question.id, outgoing.last().id)
    }

    @Test fun syntheticSystemPromptDoesNotHideEntrySource() {
        val system = UIMessage.system("Original model system prompt")
        val source = UIMessage.user(entryContextMessage("entry-abc", "静态出行背景"))
        val question = UIMessage.user("问路")
        val outgoing = withEntryContextForFirstRemoteTurn(listOf(system,source,question))
        assertTrue(outgoing.last().toText().contains("静态出行背景"))
        assertTrue(outgoing.last().toText().contains("问路"))
    }

    @Test fun editedSourceReachesLaterRemoteTurnsWithoutChangingChatHistory() {
        val source = UIMessage.user(entryContextMessage("entry-abc", "原始行程"))
        val question = UIMessage.user("今天天气怎样")
        val answer = UIMessage.assistant("可能下雨")
        val followup = UIMessage.user("那明天呢")
        val history = listOf(source, question, answer, followup)
        val outgoing = withEntryContextForFirstRemoteTurn(history)
        assertTrue(outgoing.last().toText().contains("原始行程"))
        assertTrue(outgoing.last().toText().contains("那明天呢"))
        assertEquals(followup, history.last())
        assertEquals(followup.id, outgoing.last().id)
    }

    @Test fun entryPreviewDoesNotLeakControlMarkers() {
        val source = UIMessage.user(entryContextMessage("entry-abc", "松本酒店\n入住两晚"))
        assertEquals("松本酒店\n入住两晚", nativeEntrySourceOrNull(source))
        assertEquals(null, nativeEntrySourceOrNull(UIMessage.user("普通消息")))
    }

    @Test fun ordinaryChatsRemainUnchanged() {
        val messages = listOf(UIMessage.user("你好"), UIMessage.user("还在吗"))
        assertEquals(messages, withEntryContextForFirstRemoteTurn(messages))
    }
}
