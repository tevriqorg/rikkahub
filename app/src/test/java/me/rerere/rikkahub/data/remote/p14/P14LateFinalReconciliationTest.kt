package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class P14LateFinalReconciliationTest {
    private val localConversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val assistantId = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val userId = Uuid.parse("33333333-3333-4333-8333-333333333333")
    private fun nativeUser(id: Uuid = userId): UIMessage =
        UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Question")))
    private fun message(id:String,role:String,body:String,time:Long=1_000L):P14ProductMessageEntity =
        P14ProductMessageEntity(id,"p14-c-local",role,body,deliveryState="SYNCED",createdAtEpochMs=time)
    private fun original(vararg msgs: UIMessage): Conversation =
        Conversation.ofId(localConversationId,assistantId,messages=msgs.map(MessageNode::of))
    private val remoteUser get()=message(userId.toString(),"user","Question",1_000L)
    private val final get()=message("h-server-final","assistant","Canonical answer",2_000L)

    @Test fun delayedFinalAppearsOnceAndSurvivesRepeatedReplay() {
        val base=original(nativeUser())
        val first=mergeP14DurableFinals(base,listOf(remoteUser,final))
        assertEquals(listOf(MessageRole.USER,MessageRole.ASSISTANT),first.currentMessages.map { it.role })
        assertEquals("Canonical answer",first.currentMessages.last().toText())
        assertEquals(p14ServerAssistantUuid(final.messageId),first.currentMessages.last().id)
        assertSame(first,mergeP14DurableFinals(first,listOf(remoteUser,final)))
    }

    @Test fun nativeSseAnswerIsNeverDuplicatedOrReplaced() {
        val answer=UIMessage(role=MessageRole.ASSISTANT,parts=listOf(UIMessagePart.Text("Canonical answer")))
        val base=original(nativeUser(),answer)
        assertSame(base,mergeP14DurableFinals(base,listOf(remoteUser,final)))
        assertEquals(answer.id,base.currentMessages.last().id)
    }

    @Test fun transportProgressIsNotASyntheticAnswer() {
        val progress=message("h-status","assistant","⏳ Working — 10 min — iteration 31/500, waiting for provider response (streaming)",1_500L)
        val review=message("h-review","assistant","💾 Self-improvement review: updated notes",1_600L)
        val base=original(nativeUser())
        assertSame(base,mergeP14DurableFinals(base,listOf(remoteUser,progress,review)))
        assertEquals(progress.bodyText,recentP14Progress(listOf(progress),1_550L))
        assertNull(recentP14Progress(listOf(progress,final),2_100L))
        assertNull(recentP14Progress(listOf(progress),130_000L))
    }

    @Test fun foreignOrUnownedConversationsCannotBeProjected() {
        val base=original(nativeUser())
        val wrong=message("different-native-user","user","Foreign",1_000L)
        assertSame(base,mergeP14DurableFinals(base,listOf(wrong,final)))
        assertSame(base,mergeP14DurableFinals(base,listOf(final)))
    }

    @Test fun repeatedResponseTextAcrossDistinctUserTurnsKeepsIndependentServerIds() {
        val anotherUser=Uuid.parse("44444444-4444-4444-8444-444444444444")
        val base=original(nativeUser(),nativeUser(anotherUser))
        val rows=listOf(remoteUser,message("h-first","assistant","Yes",1_200L),
            message(anotherUser.toString(),"user","Question",3_000L),
            message("h-second","assistant","Yes",3_500L))
        val result=mergeP14DurableFinals(base,rows)
        assertEquals(4,result.currentMessages.size)
        assertEquals(2,result.currentMessages.count{it.role==MessageRole.ASSISTANT})
        assertEquals(2,result.currentMessages.filter{it.role==MessageRole.ASSISTANT}.map{it.id}.toSet().size)
    }
}
