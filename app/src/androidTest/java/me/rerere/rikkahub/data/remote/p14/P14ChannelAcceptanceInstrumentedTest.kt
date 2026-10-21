package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import okio.Buffer
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import me.rerere.rikkahub.ui.activity.P14EntryActivity
import org.junit.Rule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Runner-only synthetic product-path acceptance; never a Hermes/P20 execution substitute. */
@RunWith(AndroidJUnit4::class)
class P14ChannelAcceptanceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val prefs get()=context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE)
    private val http=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS)
        .readTimeout(5,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build()
    private fun product()=P14ProductRepository(context,http)
    private fun remote()=P14RemoteRepository(context,GlobalContext.get().get<SettingsStore>(),http)

    /** A: actual native metadata -> durable Outbox -> Server reply/quote event -> local replay. */
    @Test fun replyAndQuoteThroughNativeOutbox():Unit = runBlocking {
        val runtime=guardRuntime()
        val repository=product()
        val directory=repository.directoryPage(0,100).single { it.bodyText=="task4-device-A" }
        val conversation=requireNotNull(repository.conversationForId(directory.conversationId))
        val localId=requireNotNull(conversation.localConversationId) { "Run native Reply on device A" }
        val assistant=requireNotNull(conversation.assistantId)
        val model=requireNotNull(conversation.modelId)
        repository.loadEntryHistory(directory.entryId)
        val source=repository.messages(directory.conversationId).first().single { it.bodyText=="task4-device-A" }
        val control=P14RemoteControlClient(http)
        val state=control.getMessageState(ProviderSetting.OpenAI(baseUrl=runtime.baseUrl,apiKey=runtime.bearerToken),
            directory.conversationId,Uuid.parse(source.messageId))
        val before=repository.currentCursor()
        val created=mutableListOf<Pair<String,Boolean>>()
        for(quoted in listOf(false,true)) {
            val id=Uuid.random()
            val reply=buildJsonObject {
                put("message_id",source.messageId)
                if(quoted) put("quote",buildJsonObject {
                    put("text",state.bodyText);put("source_revision",state.revision)
                    put("start_char",0);put("end_char",state.bodyText.length)
                })
            }
            val text=if(quoted) "task4-native-quote" else "task4-native-reply"
            val message=UIMessage(id,MessageRole.USER,listOf(UIMessagePart.Text(text,metadata=buildJsonObject { put("p14_reply",reply) })))
            val accepted=repository.acceptRikkaMessage(conversation.externalSessionId,localId,message,assistant,model)
            assertEquals(directory.entryId,accepted.entryId)
            assertEquals(directory.conversationId,accepted.conversationId)
            val outbox=P14ProductDatabase.get(context).dao().pendingOutbox().single { it.messageId==id.toString() }
            val pending=Json.parseToJsonElement(outbox.payloadJson).jsonObject
            assertEquals(source.messageId,pending["reply_to_message_id"]?.jsonPrimitive?.content)
            assertEquals(quoted,pending["quote"] is JsonObject)
            assertTrue(repository.syncAndFlushActive())
            assertFalse(P14ProductDatabase.get(context).dao().pendingOutbox().any { it.clientOperationId==accepted.clientOperationId })
            created.add(id.toString() to quoted)
        }
        val events=jsonRequest(runtime,"GET","sync/events?after="+before+"&limit=500").jsonObject["events"]!!.jsonArray
        for((id,quoted) in created) {
            val event=events.single { it.jsonObject["entity_id"]?.jsonPrimitive?.content==id }.jsonObject
            val reply=event["payload"]!!.jsonObject["message"]!!.jsonObject["reply"]!!.jsonObject
            assertEquals(source.messageId,reply["reply_to_message_id"]?.jsonPrimitive?.content)
            if(quoted) {
                val quote=reply["quote"]!!.jsonObject
                assertEquals(state.bodyText,quote["text"]?.jsonPrimitive?.content)
                assertEquals(state.revision,quote["source_revision"]?.jsonPrimitive?.int)
            } else assertEquals(JsonNull,reply["quote"])
            assertEquals(1,repository.messages(directory.conversationId).first().count { it.messageId==id })
        }
        evidence("replyAndQuoteThroughNativeOutbox PASS: actual native reply/quote metadata persisted before HTTP, Outbox ACKed, authoritative references validated and replayed once; no Agent reply claimed")
    }

    /** B, network disabled externally: only durable enqueue, no fabricated success response. */
    @Test fun seedQuickActionOffline():Unit = runBlocking {
        val runtime=guardRuntime()
        val reachability=runCatching {
            http.newCall(Request.Builder().url(HEALTH).build()).execute().use { it.code }
        }
        assertTrue("The real synthetic Server must be unreachable before offline enqueue",
            reachability.exceptionOrNull() is IOException)
        val entry=product().directoryPage(0,100).single { it.bodyText=="task4-device-A" }
        // Existing online Action acceptance has cached the real Manifest; offline uses that LKG.
        val op=remote().quickActionActive(entry.conversationId,"todo.create")
        assertTrue(prefs.edit().putString("channel-quick-provider",runtime.providerId)
            .putString("channel-quick-base",runtime.baseUrl).commit())
        suspendDelivery()
        val stored=operation(op)
        assertTrue("Offline durable operation cannot be ACKed",stored.state in setOf("PENDING","IN_FLIGHT","RETRY"))
        val payload=Json.parseToJsonElement(stored.payload).jsonObject
        assertEquals(op,payload["client_operation_id"]?.jsonPrimitive?.content)
        assertEquals("todo.create",payload["action_type"]?.jsonPrimitive?.content)
        val action=requireNotNull(payload["action_id"]?.jsonPrimitive?.content)
        assertTrue(prefs.edit().putString("channel-quick-operation",op)
            .putString("channel-quick-action",action).putString("channel-quick-conversation",entry.conversationId)
            .putString("channel-quick-entry",entry.entryId)
            .putString("channel-quick-payload-hash",hash(stored.payload)).commit())
        assertNotNull(runtime)
        evidence("seedQuickActionOffline PASS: real QuickAction queue committed stable operation/action/payload with network unreachable; active delivery suspended and unique worker cancelled/awaited; restore network for actual ACK-window POST next")
    }

    /** Exact HTTP-success/local-ACK crash window. Root must force-stop after the fsynced marker. */
    @Test fun quickActionAcknowledgementWindow():Unit = runBlocking {
        guardFixture()
        assertNull(P14RuntimeCredentialStore(context).activeConnection())
        WorkManager.getInstance(context).cancelUniqueWork("p14-remote-sync").result.get(15,TimeUnit.SECONDS)
        val op=requireNotNull(prefs.getString("channel-quick-operation",null))
        val conversation=requireNotNull(prefs.getString("channel-quick-conversation",null))
        val expectedHash=requireNotNull(prefs.getString("channel-quick-payload-hash",null))
        val marker=File(context.filesDir,"task4-quick-ack-window-ready")
        if(marker.exists()) assertTrue(marker.delete())
        val intercepted=http.newBuilder().addInterceptor { chain ->
            val request=chain.request()
            val matching=request.method=="POST" && request.url.toString()==BASE+"/conversations/"+conversation+"/quick-actions" &&
                request.body?.let { body ->
                    val buffer=Buffer();body.writeTo(buffer)
                    Json.parseToJsonElement(buffer.readUtf8()).jsonObject["client_operation_id"]?.jsonPrimitive?.content==op
                }==true
            val response=chain.proceed(request)
            if(!matching) response else {
                try {
                    assertTrue("Actual QuickAction Server response must be successful",response.isSuccessful)
                    // Consume real committed response but deliberately do not return it to repository ACK code.
                    val committed=Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
                    assertEquals(prefs.getString("channel-quick-action",null),committed["action_id"]?.jsonPrimitive?.content)
                    assertEquals("IN_FLIGHT",operation(op).state)
                    assertEquals(expectedHash,hash(operation(op).payload))
                    suspendDelivery()
                    assertEquals("IN_FLIGHT",operation(op).state)
                    FileOutputStream(marker).use { out ->
                        out.write("SERVER_COMMITTED_WITHOUT_LOCAL_ACK".toByteArray());out.fd.sync()
                    }
                    evidence("quickActionAcknowledgementWindow SERVER_COMMITTED_WITHOUT_LOCAL_ACK: awaiting external process death")
                    CountDownLatch(1).await(90,TimeUnit.SECONDS)
                    throw IOException("External force-stop did not arrive within bounded acknowledgement window")
                } finally { response.close() }
            }
        }.build()
        restoreQuickRuntime()
        val completed=P14RemoteRepository(context,GlobalContext.get().get<SettingsStore>(),intercepted).processPendingActive()
        fail("Crash-window process survived: completed="+completed)
    }

    /** B, after external force-stop and restored network: original committed op is retried. */
    @Test fun verifyQuickActionAfterRestart():Unit = runBlocking {
        guardFixture()
        assertNull(P14RuntimeCredentialStore(context).activeConnection())
        assertEquals("SERVER_COMMITTED_WITHOUT_LOCAL_ACK",
            File(context.filesDir,"task4-quick-ack-window-ready").readText())
        val op=requireNotNull(prefs.getString("channel-quick-operation",null))
        assertEquals("IN_FLIGHT",operation(op).state)
        restoreQuickRuntime()
        val action=requireNotNull(prefs.getString("channel-quick-action",null))
        val conversation=requireNotNull(prefs.getString("channel-quick-conversation",null))
        val entry=requireNotNull(prefs.getString("channel-quick-entry",null))
        assertEquals(prefs.getString("channel-quick-payload-hash",null),hash(operation(op).payload))
        assertTrue(operation(op).state in setOf("PENDING","IN_FLIGHT","RETRY","ACKED"))
        assertTrue(remote().processPendingActive())
        assertEquals("ACKED",operation(op).state)
        // Explicit same-operation replay tests an ambiguous-response retry; no new ID is minted.
        P14RemoteDatabase.get(context).dao().setOperationState(op,"IN_FLIGHT")
        assertTrue(remote().processPendingActive())
        assertEquals("ACKED",operation(op).state)
        assertEquals(prefs.getString("channel-quick-payload-hash",null),hash(operation(op).payload))
        val repository=product()
        assertTrue(repository.replayActive())
        repository.hydrateEntryState(entry,conversation)
        assertEquals(1,repository.actions(conversation).first().count { it.actionId==action })
        val todo=remote().workItems("todo",0,100).single { it.source=="action" && it.sourceId==action }
        assertEquals("todo.create",todo.kind)
        assertEquals(entry,todo.entryId)
        assertEquals(conversation,todo.conversationId)
        FileOutputStream(File(context.filesDir,"task4-quick-proof.json")).use { out ->
            out.write(buildJsonObject { put("operation_id",op);put("action_id",action);put("conversation_id",conversation) }.toString().toByteArray())
            out.fd.sync()
        }
        evidence("verifyQuickActionAfterRestart PASS: actual offline enqueue and Server-success-before-local-ACK process death recovered original payload and ACKed; explicit same-op retry created one authoritative typed Todo Action")
    }

    /** B, after its real confirm decision: synthetic producer result is consumed by native replay/views. */
    @Test fun consumeProducerResultAndTypedViews():Unit = runBlocking {
        val runtime=guardRuntime()
        val action=requireNotNull(args.getString("p14ActionConfirm"))
        val entry=requireNotNull(args.getString("p14MediaEntryId"))
        val conversation=requireNotNull(args.getString("p14MediaConversationId"))
        val repository=product()
        repository.hydrateEntryState(entry,conversation)
        assertEquals("approved_pending_execution",P14ProductDatabase.get(context).dao().action(action)?.status)
        val body=buildJsonObject {
            put("client_operation_id","task4-result-"+action)
            put("status","completed");put("result_text",RESULT);put("destination_ref",DESTINATION)
        }.toString()
        jsonRequest(runtime,"POST","actions/"+action+"/result",body,expected=setOf(401))
        val first=jsonRequest(runtime,"POST","actions/"+action+"/result",body,PRODUCER)
        assertEquals(first,jsonRequest(runtime,"POST","actions/"+action+"/result",body,PRODUCER))
        assertTrue(repository.replayActive())
        repository.hydrateEntryState(entry,conversation)
        val stored=requireNotNull(P14ProductDatabase.get(context).dao().action(action))
        assertEquals("completed",stored.status);assertEquals(RESULT,stored.resultText)
        assertEquals(DESTINATION,stored.destinationRef)
        val execute=remote().workItems("execute",0,100).single { it.source=="action" && it.sourceId==action }
        assertEquals(entry,execute.entryId);assertEquals(conversation,execute.conversationId)
        assertEquals("completed",execute.status);assertEquals(RESULT,execute.resultText)
        assertEquals(DESTINATION,execute.destinationRef)
        val idea=remote().workItems("idea",0,100).single { it.entryId==entry }
        assertEquals("entry",idea.source);assertEquals("idea",idea.kind)
        assertEquals(conversation,idea.conversationId)
        val activity=Uuid.random().toString()
        val activityBody=buildJsonObject {
            put("client_operation_id",Uuid.random().toString());put("activity_id",activity)
            put("kind","research.synthetic");put("entry_id",entry);put("conversation_id",conversation)
            put("state","running");put("phase","Synthetic research acceptance");put("progress_percent",37)
        }.toString()
        jsonRequest(runtime,"POST","activities",activityBody,PRODUCER,expected=setOf(200,201))
        val research=remote().workItems("research",0,100).single { it.source=="activity" && it.sourceId==activity }
        assertEquals(entry,research.entryId);assertEquals(conversation,research.conversationId)
        assertEquals("running",research.status);assertEquals(37,research.progressPercent)
        assertEquals("Synthetic research acceptance",research.phase)
        assertWorkUi("execute", RESULT, execute.kind+" · "+execute.status)
        assertWorkUi("research", "Synthetic research acceptance", "research.synthetic · running", "37%")
        val detail=Intent(context,P14EntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(P14EntryActivity.EXTRA_CONVERSATION_ID,conversation)
        ActivityScenario.launch<P14EntryActivity>(detail).use {
            compose.waitUntil(20_000) {
                runCatching {
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(RESULT))
                }.isSuccess
            }
            compose.onNodeWithText(RESULT).assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Destination: "+DESTINATION))
            compose.onNodeWithText("Destination: "+DESTINATION).assertIsDisplayed()
        }
        evidence("consumeProducerResultAndTypedViews PASS: actual Android-confirmed Action consumed producer-only Result through native replay, typed Execute/Research UI and real Action Result/Destination detail UI; Idea/Research preserve Entry identity; synthetic producer is not Hermes execution")
    }

    /** B after materializeRemoteMedia: actual Android decoder preparation/play/completion on cached WAV. */
    @Test fun playMaterializedVoiceToCompletion():Unit = runBlocking {
        guardRuntime()
        val id=requireNotNull(prefs.getString("remote-media-local-id",null))
        val dao=P14ProductDatabase.get(context).dao()
        val before=requireNotNull(dao.attachment(id))
        assertEquals("VOICE",before.kind);assertEquals("audio/wav",before.mimeType)
        assertEquals(args.getString("p14MediaAttachmentId"),before.remoteAttachmentId)
        val locator=requireNotNull(before.localLocator)
        val bytes=context.contentResolver.openInputStream(Uri.parse(locator))!!.use { it.readBytes() }
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        val complete=CountDownLatch(1)
        val errors=java.util.concurrent.atomic.AtomicReference<String?>(null)
        val player=MediaPlayer()
        try {
            player.setDataSource(context,Uri.parse(locator))
            player.setOnCompletionListener { complete.countDown() }
            player.setOnErrorListener { _,what,extra -> errors.set("MediaPlayer "+what+"/"+extra);complete.countDown();true }
            player.prepare()
            assertTrue("Native decoder must report positive duration",player.duration>0)
            player.start()
            assertTrue("Native playback must complete within 15 seconds",complete.await(15,TimeUnit.SECONDS))
            assertNull(errors.get())
        } finally { player.release() }
        assertEquals(before,dao.attachment(id))
        val after=context.contentResolver.openInputStream(Uri.parse(locator))!!.use { it.readBytes() }
        assertEquals(digest,MessageDigest.getInstance("SHA-256").digest(after).toList())
        assertTrue(recoverP14AttachmentPart(before) is UIMessagePart.Audio)
        evidence("playMaterializedVoiceToCompletion PASS: real MediaPlayer prepared/started/completed cached synthetic WAV; VOICE identity and source hash unchanged; no physical speaker/microphone claim")
    }

    private fun suspendDelivery() {
        assertTrue(context.getSharedPreferences("p14-rikkahub-runtime",Context.MODE_PRIVATE).edit()
            .remove("active_provider").remove("active_base").commit())
        WorkManager.getInstance(context).cancelUniqueWork("p14-remote-sync").result.get(15,TimeUnit.SECONDS)
    }

    private fun restoreQuickRuntime():P14RuntimeConnection {
        guardFixture()
        val provider=requireNotNull(prefs.getString("channel-quick-provider",null))
        val base=requireNotNull(prefs.getString("channel-quick-base",null))
        require(base==BASE)
        val store=P14RuntimeCredentialStore(context)
        requireNotNull(store.load(provider,base)) { "Original encrypted credential must survive process death" }
        store.markActive(provider,base)
        return guardRuntime()
    }

    private fun assertWorkUi(view:String,marker:String,vararg labels:String) {
        val intent=Intent(context,P14EntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<P14EntryActivity>(intent).use {
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(view).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(view).performClick()
            compose.waitUntil(20_000) {
                runCatching {
                    compose.onNodeWithTag("p14-entry-list").performScrollToNode(hasText(marker))
                }.isSuccess
            }
            compose.onNodeWithText(marker).assertIsDisplayed()
            labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        }
    }

    private fun guardFixture() {
        require(args.getString("p14OwnerToken")=="task4-owner-synthetic-only") { "Synthetic acceptance only" }
        require(args.getString("p14BaseUrl")==BASE) { "Runner emulator fixture only" }
        require(context.packageName=="me.rerere.rikkahub.debug")
    }

    private fun guardRuntime():P14RuntimeConnection {
        guardFixture()
        return requireNotNull(P14RuntimeCredentialStore(context).activeConnection()).also {
            require(it.baseUrl==BASE) { "Unexpected runtime target" }
        }
    }

    private fun jsonRequest(runtime:P14RuntimeConnection,method:String,path:String,body:String?=null,
        token:String=runtime.bearerToken,expected:Set<Int> = setOf(200)):JsonElement {
        val request=Request.Builder().url(BASE+"/"+path).header("Authorization","Bearer "+token)
        if(method=="POST") request.post(requireNotNull(body).toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use {
            assertTrue("Unexpected synthetic HTTP status for "+path,it.code in expected)
            val text=requireNotNull(it.body).string()
            return Json.parseToJsonElement(text)
        }
    }

    private data class SavedOperation(val payload:String,val state:String)
    private fun operation(id:String):SavedOperation =
        P14RemoteDatabase.get(context).openHelper.readableDatabase
            .query("SELECT payload,state FROM p14_operations WHERE operation_id=?",arrayOf(id)).use {
                assertTrue("Durable operation must exist",it.moveToFirst())
                SavedOperation(it.getString(0),it.getString(1))
            }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun evidence(text:String) { android.util.Log.i("P14_TASK4",text) }
    private companion object {
        const val BASE="http://10.0.2.2:8014/v1"
        const val HEALTH="http://10.0.2.2:8014/healthz"
        const val PRODUCER="task4-producer-synthetic-only"
        const val RESULT="Synthetic Android-confirmed ActionResult; no Hermes execution"
        const val DESTINATION="https://example.invalid/p14-task4-result"
    }
}
