package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Actual isolated Room/Outbox/draft lifetime, no production credentials or Hermes. */
@RunWith(AndroidJUnit4::class)
class P14EntryRevisionInstrumentedTest {
    private class Scoped(base:Context,val prefix:String):ContextWrapper(base) {
        val prefs=mutableSetOf<String>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            val id="$prefix-$name";prefs.add(id);return baseContext.getSharedPreferences(id,mode)
        }
        fun clear()=prefs.forEach { baseContext.deleteSharedPreferences(it) }
    }
    private suspend fun fixture(block:suspend(Scoped,P14ProductDatabase,P14ProductRepository)->Unit) {
        val c=Scoped(InstrumentationRegistry.getInstrumentation().targetContext,"p14-revision-"+Uuid.random())
        val db=Room.inMemoryDatabaseBuilder(c,P14ProductDatabase::class.java).build()
        try { block(c,db,P14ProductRepository(c,OkHttpClient(),db)) } finally { db.close();c.clear() }
    }
    private suspend fun capture(repo:P14ProductRepository):Pair<UIMessage,P14AcceptedMutation> {
        val message=UIMessage(id=Uuid.random(),role=MessageRole.USER,parts=listOf(UIMessagePart.Text("原始标题\n原始内容")))
        val id=Uuid.random().toString();return message to repo.acceptRikkaMessage(id,id,message,null,null,true)
    }
    @Test fun revisionsRetainOriginalCaptureAndUseSameConversation()=runBlocking { fixture { _,db,repo ->
        val (_,a)=capture(repo)
        repo.acceptEntryRevision(a.conversationId,"修订标题\n第一版正文",1)
        repo.acceptEntryRevision(a.conversationId,"修订标题\n第二版正文",2)
        assertEquals("原始标题\n原始内容",repo.entry(a.entryId)!!.bodyText)
        assertEquals(3,repo.message(a.messageId)!!.revision)
        val queued=db.dao().pendingOutbox();assertEquals(3,queued.size)
        assertEquals(3,queued.map { it.clientOperationId }.toSet().size)
        assertTrue(queued.all { it.conversationId==a.conversationId && it.entryId==a.entryId })
        assertTrue(queued.first().payloadJson.contains("原始内容"))
        assertEquals("修订标题\n第二版正文",repo.inboxRows().first().single().bodyText)
        val turn=repo.acceptEntryContinuation(a.conversationId,"请讨论这条修订")
        assertEquals(a.conversationId,turn.conversationId)
        val payload=Json.parseToJsonElement(db.dao().pendingOutbox().last().payloadJson).jsonObject
        assertEquals(a.messageId,payload["reply_to_message_id"]!!.jsonPrimitive.content)
        assertEquals("修订标题\n第二版正文",payload["quote"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("请讨论这条修订",payload["text"]!!.jsonPrimitive.content)
        assertEquals(1,db.dao().directoryPage(20,0).size)
    } }
    @Test fun oldAcknowledgementsCannotEraseLaterOfflineEdit()=runBlocking { fixture { _,db,repo ->
        val (_,a)=capture(repo)
        val first=repo.acceptEntryRevision(a.conversationId,"修订一",1)
        val last=repo.acceptEntryRevision(a.conversationId,"修订二",2)
        fun event(seq:Int,op:String,type:String,body:String,revision:Int)=buildJsonObject {
            put("event_id","event-$seq");put("sync_seq",seq);put("event_type",type);put("entity_type","message")
            put("entity_id",a.messageId);put("caused_by_operation_id",op);put("occurred_at","2026-10-30T00:00:00Z")
            put("payload",if(type.endsWith("created")) buildJsonObject {
                put("entry",buildJsonObject {put("entry_id",a.entryId);put("body_text","原始标题\n原始内容");put("created_at","2026-10-30T00:00:00Z")})
                put("message",buildJsonObject {put("message_id",a.messageId);put("conversation_id",a.conversationId);put("role","user");put("body_text",body);put("created_at","2026-10-30T00:00:00Z")})
            } else buildJsonObject {put("message_id",a.messageId);put("body_text",body);put("revision",revision)})
        }
        suspend fun apply(e:JsonObject)=repo.applyLiveEvents(buildJsonObject {put("type","events");put("events",buildJsonArray { add(e) })}.toString())
        apply(event(1,a.clientOperationId,"conversation.message.created","原始标题\n原始内容",1))
        assertEquals("修订二",repo.message(a.messageId)!!.bodyText)
        apply(event(2,first.clientOperationId,"conversation.message.edited","修订一",2))
        assertEquals("修订二",repo.message(a.messageId)!!.bodyText)
        val final=event(3,last.clientOperationId,"conversation.message.edited","修订二",3)
        apply(final);apply(final)
        assertEquals("SYNCED",repo.message(a.messageId)!!.deliveryState)
        assertEquals(3L,repo.currentCursor())
        assertEquals("原始标题\n原始内容",repo.entry(a.entryId)!!.bodyText)
    } }
    @Test fun unknownSourceCannotBecomeAnUnrelatedRecentMessage()=runBlocking { fixture { _,db,repo ->
        db.dao().upsertEntry(P14EntryEntity("entry","原始记录",1000))
        db.dao().upsertConversation(P14ProductConversationEntity("conversation","entry","external",null,1000))
        db.dao().upsertMessage(P14ProductMessageEntity("recent","conversation","user","后来的一句聊天",deliveryState="SYNCED",createdAtEpochMs=9000))
        assertNull(repo.sourceMessage("conversation").first())
        assertTrue(runCatching { repo.acceptEntryRevision("conversation","不应覆盖") }.isFailure)
        assertEquals("后来的一句聊天",repo.message("recent")!!.bodyText)
    } }
    @Test fun editorDraftRestoresSelectionAndUndoWithoutTouchingCapture()=runBlocking { fixture { c,_,repo ->
        val (_,a)=capture(repo)
        val before=P14EditorSnapshot("原始标题","原始内容",focused="body")
        val current=before.copy(body="未提交的长文本\n第二行",bodyStart=3,bodyEnd=3)
        val draft=P14EntryEditDraft(a.entryId,a.messageId,1,current,listOf(before))
        P14EntryEditDraftStore(c).save(draft)
        assertEquals(draft,P14EntryEditDraftStore(c).read(a.entryId))
        assertEquals("原始标题\n原始内容",repo.entry(a.entryId)!!.bodyText)
        assertEquals(1,repo.message(a.messageId)!!.revision)
    } }
}
