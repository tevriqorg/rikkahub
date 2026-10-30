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

    @Test fun laterRemoteTurnsDoNotReinsertContext() {
        val source = UIMessage.user(entryContextMessage("entry-abc", "原始行程"))
        val question = UIMessage.user("今天天气怎样")
        val answer = UIMessage.assistant("可能下雨")
        val followup = UIMessage.user("那明天呢")
        val history = listOf(source, question, answer, followup)
        assertEquals(history, withEntryContextForFirstRemoteTurn(history))
    }

    @Test fun ordinaryChatsRemainUnchanged() {
        val messages = listOf(UIMessage.user("你好"), UIMessage.user("还在吗"))
        assertEquals(messages, withEntryContextForFirstRemoteTurn(messages))
    }
}
