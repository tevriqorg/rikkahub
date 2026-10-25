package me.rerere.rikkahub.ui.activity

import me.rerere.rikkahub.data.remote.p14.P14InboxRow
import me.rerere.rikkahub.data.remote.p14.P14ProductMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TickTick-like Inbox is a projection of durable P14 Entries, never a second task DB. */
class P14InboxPresentationTest {
    private fun row(id:String,content:String)=P14InboxRow(
        entryId=id,conversationId="conversation-$id",bodyText=content,
        createdAtEpochMs=1_000L,pinned=false,archived=false,
    )
    private fun msg(id:String,text:String,role:String,at:Long=1_000L)=P14ProductMessageEntity(
        messageId=id,conversationId="conversation-1",role=role,bodyText=text,
        deliveryState="SYNCED",createdAtEpochMs=at,
    )

    @Test fun firstNonEmptyLineIsInboxTitleAndRestIsPreview() {
        assertEquals("预约长野酒店",p14InboxTitle("\n 预约长野酒店 \n需要双床\n靠近车站", "entry-1"))
        assertEquals("需要双床 靠近车站",p14InboxPreview("\n预约长野酒店\n需要双床\n靠近车站"))
        assertEquals("未命名条目",p14InboxTitle("  \n\t","entry-2"))
    }

    @Test fun searchFiltersByWholeSourceButNeverInventsNewIdentity() {
        val entries=listOf(row("1","旅行安排\n旭川 -> 札幌"),row("2","买内存"),row("3","今日待办"))
        assertEquals(listOf("1"),p14InboxFiltered(entries,"札幌").map { it.entryId })
        assertEquals(listOf("1","2","3"),p14InboxFiltered(entries,"   ").map { it.entryId })
        assertEquals(listOf("1"),p14InboxFiltered(entries,"旅行").map { it.entryId })
    }

    @Test fun originalCaptureAppearsInSourceAndNotTwiceInConversation() {
        val source=msg("capture","买一台服务器",role="user",at=1_000L)
        val answer=msg("agent","先比较内存和噪音",role="assistant",at=3_000L)
        val followup=msg("followup","那 DDR5 呢？",role="user",at=4_000L)
        assertEquals(listOf(answer,followup),
            p14InboxConversationMessages(listOf(source,answer,followup),"买一台服务器",1_000L))
    }

    @Test fun laterIdenticalUserMessageMustNotBeRemoved() {
        val source=msg("capture","调查 TickTick 收件箱",role="user",at=1_000L)
        val later=msg("later","调查 TickTick 收件箱",role="user",at=100_000L)
        assertEquals(listOf(later),
            p14InboxConversationMessages(listOf(source,later),"调查 TickTick 收件箱",1_000L))
    }

    @Test fun doNotHideAssistantOrMessagesWithoutAuthoritativeSource() {
        val first=msg("assistant","原始记录",role="assistant")
        val other=msg("user","另一个内容",role="user")
        assertEquals(listOf(first,other),
            p14InboxConversationMessages(listOf(first,other),"原始记录",1_000L))
        assertTrue(p14InboxConversationMessages(emptyList(),"",0L).isEmpty())
    }
}
