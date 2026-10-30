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
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
        repo.acceptEntryRevision(a.conversationId,"修订标题\n第二版正文",1)
        assertEquals(3,db.dao().pendingOutbox().size) // recovery after commit/before draft-clear is idempotent

        assertTrue(queued.all { it.conversationId==a.conversationId && it.entryId==a.entryId })
        assertTrue(queued.first().payloadJson.contains("原始内容"))
        assertEquals("修订标题\n第二版正文",repo.inboxRows().first().single().bodyText)
        val turn=repo.acceptEntryContinuation(a.conversationId,"请讨论这条修订")
        assertEquals(a.conversationId,turn.conversationId)
        val payload=Json.parseToJsonElement(db.dao().pendingOutbox().last().payloadJson).jsonObject
        assertEquals(a.messageId,payload["reply_to_message_id"]!!.jsonPrimitive.content)
        assertEquals("修订标题\n第二版正文",payload["quote"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("请讨论这条修订",payload["text"]!!.jsonPrimitive.content)
        assertEquals(3,payload["quote"]!!.jsonObject["source_revision"]!!.jsonPrimitive.int)
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
    @Test fun pinAndArchiveUseDurableOrganizationNotTaskCompletion()=runBlocking { fixture { _,db,repo ->
        val (_,a)=capture(repo);repo.acceptOrganization(a.conversationId,pinned=true,archived=true)
        assertTrue(repo.conversation(a.conversationId)!!.pinned);assertTrue(repo.conversation(a.conversationId)!!.archived)
        val operation=db.dao().pendingOutbox().last();assertEquals("SET_CONVERSATION_STATE",operation.operationType)
        assertFalse(operation.payloadJson.contains("completed"))
        repo.acceptOrganization(a.conversationId,archived=false)
        assertEquals(2,repo.conversation(a.conversationId)!!.stateRevision)
        assertEquals(1,repo.inboxRows().first().size);assertTrue(repo.inboxRows(true).first().isEmpty())
        assertEquals("原始标题\n原始内容",repo.entry(a.entryId)!!.bodyText)
    } }
    private data class Call(val path:String,val payload:JsonObject)
    private fun network(c:Scoped,calls:MutableList<Call>,edit:(JsonObject)->Int):OkHttpClient {
        P14RuntimeCredentialStore(c).apply {save("synthetic-provider","http://127.0.0.1:1/v1","synthetic-device-token-only");markActive("synthetic-provider","http://127.0.0.1:1/v1")}
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request();check(request.url.host=="127.0.0.1")
            var status=200
            val path=request.url.encodedPath
            val response=if(request.method=="POST") {
                val buffer=Buffer();request.body!!.writeTo(buffer);val payload=Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                calls.add(Call(path,payload));if(path.endsWith("/edit"))status=edit(payload)
                else check(path=="/v1/entries/capture") {"Unexpected mutation route: $path"}
                "{}"
            } else when {
                path.endsWith("/remote-manifest")->"""{"schema_version":"p14.remote-manifest/v1","manifest_version":1,"minimum_client_protocol":1,"maximum_client_protocol":1,"compatibility":"COMPATIBLE","enabled_views":["chat","inbox","todo","idea"],"message_capabilities":["text","reply","edit"]}"""
                path.endsWith("/sync/events")->"""{"events":[],"next_after":0,"watermark":0,"has_more":false}"""
                path.endsWith("/bootstrap/entries")->"""{"items":[],"watermark":0,"has_more":false}"""
                "/messages/" in path->"""{"revision":3,"body_text":"其他设备的修订","deleted":false}"""
                else->error("Unexpected synthetic GET: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic").body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }
    @Test fun retryUsesSameEditIdentityAndNeverPostsAnAiTurn()=runBlocking {fixture { c,db,_ ->
        val calls=mutableListOf<Call>();var attempts=0
        val repository=P14ProductRepository(c,network(c,calls){attempts++;if(attempts==1)503 else 200},db)
        val (_,a)=capture(repository);val edit=repository.acceptEntryRevision(a.conversationId,"离线修订",1)
        val later=repository.acceptEntryRevision(a.conversationId,"第二次离线修订",2)
        repository.syncAndFlushActive()
        assertTrue(repository.hasPendingDelivery())
        assertTrue(repository.pendingChatDelivery(a.conversationId)!!.contains("重试"))
        assertEquals(listOf("RETRY","PENDING"),db.dao().messageEdits(a.messageId).map {it.state})
        assertEquals(1,calls.count {it.path.endsWith("/edit")})
        repository.syncAndFlushActive();assertTrue(db.dao().pendingOutbox().isEmpty())
        assertFalse(repository.hasPendingDelivery());assertNull(repository.pendingChatDelivery(a.conversationId))
        assertEquals(listOf("/v1/entries/capture")+List(3){"/v1/conversations/${a.conversationId}/messages/${a.messageId}/edit"},calls.map {it.path})
        val edits=calls.filter {it.path.endsWith("/edit")};assertEquals(edits[0].payload,edits[1].payload)
        assertEquals(edit.clientOperationId,edits[0].payload["client_operation_id"]!!.jsonPrimitive.content)
        assertEquals(later.clientOperationId,edits[2].payload["client_operation_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(1,1,2),edits.map {it.payload["base_revision"]!!.jsonPrimitive.int})
        assertEquals("第二次离线修订",repository.message(a.messageId)!!.bodyText)
        assertEquals("原始标题\n原始内容",repository.entry(a.entryId)!!.bodyText)
    }
        fixture { c,db,_ ->
            val calls=mutableListOf<Call>();val events=mutableListOf<JsonObject>();var failures=1
            val base=network(c,calls){200}
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                val r=chain.request();val path=r.url.encodedPath;check(r.url.host=="127.0.0.1")
                var code=200
                val body=if(r.method=="POST" && path.endsWith("/messages")) {
                    val buffer=Buffer();r.body!!.writeTo(buffer);val payload=Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                    calls.add(Call(path,payload))
                    if(failures-->0) code=503 else {
                        val cid=path.substringAfter("/conversations/").substringBefore('/')
                        fun event(role:String,id:String,text:String)=buildJsonObject {
                            put("event_id","exchange-${events.size+1}");put("sync_seq",events.size+1);put("event_type","conversation.message.created");put("entity_type","message");put("entity_id",id);put("occurred_at","2026-10-30T04:00:00Z")
                            put("payload",buildJsonObject {put("message",buildJsonObject {put("message_id",id);put("conversation_id",cid);put("role",role);put("body_text",text)})})
                        }
                        events.add(event("user",payload["message_id"]!!.jsonPrimitive.content,payload["text"]!!.jsonPrimitive.content))
                        events.add(event("assistant","answer-${events.size}","合成 AI 回复："+payload["text"]!!.jsonPrimitive.content))
                    }
                    "{}"
                } else if(path.endsWith("/sync/events")) {
                    val after=r.url.queryParameter("after")!!.toLong()
                    buildJsonObject {put("events",JsonArray(events.filter {it["sync_seq"]!!.jsonPrimitive.long>after}));put("has_more",false);put("next_after",events.size);put("watermark",events.size)}.toString()
                } else return@addInterceptor base.newCall(r).execute()
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val repository=P14ProductRepository(c,client,db)
            val (_,capture)=capture(repository)
            repository.acceptEntryRevision(capture.conversationId,"修改后的标题与正文",1)
            repository.syncAndFlushActive()
            val first=repository.acceptEntryContinuation(capture.conversationId,"怎么去东京站？")
            // Simulate the exact old candidate payload already persisted on an owner device.
            val queued=db.dao().pendingOutbox().single()
            val old=Json.parseToJsonElement(queued.payloadJson).jsonObject
            db.dao().repairPendingPayload(queued.clientOperationId,JsonObject(old+("quote" to JsonObject(old["quote"]!!.jsonObject-"source_revision"))).toString())
            repository.syncAndFlushActive();assertTrue(repository.hasPendingDelivery())
            val fixed=db.dao().pendingOutbox().single()
            assertEquals(queued.messageId,fixed.messageId);assertEquals(queued.clientOperationId,fixed.clientOperationId)
            assertEquals(2,Json.parseToJsonElement(fixed.payloadJson).jsonObject["quote"]!!.jsonObject["source_revision"]!!.jsonPrimitive.int)
            repository.syncAndFlushActive();assertFalse(repository.hasPendingDelivery())
            assertEquals(calls.filter {it.path.endsWith("/messages")}[0].payload,calls.filter {it.path.endsWith("/messages")}[1].payload)
            assertEquals("SYNCED",repository.message(first.messageId)!!.deliveryState)
            repository.acceptEntryContinuation(capture.conversationId,"然后怎么回来？");repository.syncAndFlushActive()
            val history=repository.messages(capture.conversationId,50).first()
            assertEquals(2,history.count {it.role=="assistant"});assertEquals(5,history.size)
            assertTrue(history.all {it.conversationId==capture.conversationId})
            assertEquals(1,db.dao().directoryPage(20,0).size)
            assertEquals("原始标题\n原始内容",repository.entry(capture.entryId)!!.bodyText)
        }
    }
    private suspend fun conflict(keepLocal:Boolean)=fixture { c,db,_ ->
        val calls=mutableListOf<Call>()
        val repository=P14ProductRepository(c,network(c,calls){if(it["base_revision"]!!.jsonPrimitive.int==1)409 else 200},db)
        val (_,a)=capture(repository);repository.acceptEntryRevision(a.conversationId,"较早本机修订",1)
        repository.acceptEntryRevision(a.conversationId,"本机修订",2)
        repository.syncAndFlushActive()
        assertEquals("本机修订",repository.message(a.messageId)!!.bodyText)
        assertEquals(1,repository.editConflicts(a.conversationId).first().size)
        assertEquals(listOf("CONFLICT","PENDING"),db.dao().messageEdits(a.messageId).map {it.state})
        repository.resolveConflicts(a.conversationId,keepLocal)
        assertTrue(repository.editConflicts(a.conversationId).first().isEmpty())
        assertEquals(if(keepLocal)1 else 0,db.dao().messageEdits(a.messageId).size)
        if(keepLocal) {
            assertEquals("本机修订",repository.message(a.messageId)!!.bodyText)
            assertEquals(4,repository.message(a.messageId)!!.revision)
            repository.syncAndFlushActive();assertEquals(3,calls.last().payload["base_revision"]!!.jsonPrimitive.int)
        } else {assertEquals("其他设备的修订",repository.message(a.messageId)!!.bodyText);assertEquals("SYNCED",repository.message(a.messageId)!!.deliveryState)}
        assertEquals("原始标题\n原始内容",repository.entry(a.entryId)!!.bodyText)
    }
    @Test fun conflictPreservesLocalAndExplicitRebaseUsesRemoteRevision()=runBlocking {conflict(true)}
    @Test fun adoptingRemoteVersionIsExplicitAndOriginalRemains()=runBlocking {conflict(false)}

    @Test fun searchFiltersWholeCacheBeforePagingAndRetainsIdentities()=runBlocking {fixture { _,db,repo ->
        for(i in 0 until 250) {
            db.dao().upsertEntry(P14EntryEntity("entry-$i","记录 $i\n目标关键词 $i",1000L+i))
            db.dao().upsertConversation(P14ProductConversationEntity("conversation-$i","entry-$i","external-$i",null,1000L+i,pinned=i==3))
        }
        db.dao().upsertEntryView(P14EntryViewEntity("entry-200","research",true))
        assertEquals(60,repo.inboxRows().first().size)
        val found=repo.inboxRows(query="目标关键词 200",view="research").first().single()
        assertEquals("entry-200",found.entryId);assertEquals("conversation-200",found.conversationId)
        assertEquals("research",found.views)
        val ids=mutableListOf<String>();for(offset in 0 until 250 step 20)ids+=repo.directoryPage(offset,20).map {it.entryId}
        assertEquals(250,ids.size);assertEquals(250,ids.toSet().size)
    }}

}
