package me.rerere.rikkahub.data.remote.p14

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** VIEWS-001: protect live stream vs final and existing data after fixed-signature APK upgrade. */
class P14AssistantDraftDisplayPolicyTest {
    private fun msg(id:String,body:String,state:String,at:Long=1_000L,role:String="assistant") =
        P14ProductMessageEntity(messageId=id,conversationId="conv-1",role=role,
            bodyText=body,deliveryState=state,createdAtEpochMs=at)

    @Test fun exactLegacyDraftWithFinalIsDisplayedOnlyOnce() {
        val draft=msg("draft:conv-1:stream-a","Completed text","STREAMING",1000L)
        val final=msg("server-final-id","Completed text","SYNCED",1400L)
        assertEquals(listOf(final),p14VisibleEntryMessages(listOf(draft,final)))
        assertEquals(listOf(final),p14VisibleEntryMessages(listOf(final,draft)))
    }

    @Test fun unfinishedDraftRemainsVisible() {
        val draft=msg("draft:conv-1:still-streaming","partial text","STREAMING")
        val other=msg("server-final","another response","SYNCED",2000L)
        assertEquals(listOf(draft,other),p14VisibleEntryMessages(listOf(draft,other)))
    }

    @Test fun differentHermesFinalsMustNeverMerge() {
        val a=msg("first","different final A","SYNCED",1500L)
        val b=msg("second","different final B","SYNCED",1550L)
        assertEquals(listOf(a,b),p14VisibleEntryMessages(listOf(a,b)))
    }

    @Test fun anotherConversationOrOlderIdenticalTextDoesNotHideLiveDraft() {
        val live=msg("draft:conv-1:new","repeat","STREAMING",8_000_000L)
        val earlier=msg("prior","repeat","SYNCED",1000L)
        val other=msg("other-conv","repeat","SYNCED",8_000_100L)
            .copy(conversationId="conv-2")
        assertEquals(listOf(earlier,live,other),
            p14VisibleEntryMessages(listOf(earlier,live,other)))
    }

    @Test fun userMessageAndUnrelatedStatusesNeverFiltered() {
        val user=msg("user-1","Completed text","SYNCED",1000L,role="user")
        val draft=msg("draft:conv-1:aaa","Completed text","STREAMING",1000L)
        val final=msg("final","Completed text","SYNCED",1300L)
        val kept=p14VisibleEntryMessages(listOf(user,draft,final))
        assertEquals(listOf(user,final),kept)
        assertTrue(kept.any { it.role=="user" })
    }
}
