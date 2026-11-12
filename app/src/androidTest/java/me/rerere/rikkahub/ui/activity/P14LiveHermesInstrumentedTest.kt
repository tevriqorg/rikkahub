package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.remote.p14.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Opt-in live backend acceptance. Never included in the default synthetic suite. */
@RunWith(AndroidJUnit4::class)
class P14LiveHermesInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun remoteDiscussionKeepsEntryAndContext():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Live backend requires explicit opt-in",args.getString("p14LiveHermes")=="true")
        assertEquals("true",args.getString("p14SyntheticFixture"))
        assertTrue("Disposable emulator required",Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=instrumentation.targetContext
        val credential=P14RuntimeCredentialStore(context)
        assertNull("Never replace an existing pairing",credential.activeConnection())
        val base=requireNotNull(args.getString("p14BaseUrl"))
        assertEquals("http://10.0.2.2:18144/v1",base)
        val token=requireNotNull(args.getString("p14TestToken"))
        check(token.isNotBlank())
        val repo=P14ProductRepository(context,OkHttpClient())
        val db=P14ProductDatabase.get(context)
        // Both guard arguments and emulator hardware are checked before resetting synthetic Room.
        db.clearAllTables()
        val id=Uuid.random()
        val original="独立验收记录：旧暗号 OLD-OBSOLETE。"
        val code="KIWI-"+Uuid.random().toString().take(8)
        val updated="独立验收记录：当前暗号 $code，目的站东京站。"
        val accepted=repo.acceptRikkaMessage("p14-native-live-$id",id.toString(),UIMessage(id=id,role=MessageRole.USER,parts=listOf(UIMessagePart.Text(original))),null,null,true)
        repo.acceptEntryRevision(accepted.conversationId,updated,1)
        val output=File(context.getExternalFilesDir(null),"ci-additional/p14-live-hermes").apply {check(mkdirs()||isDirectory)}
        fun awaitTag(tag:String) {compose.waitUntil(20_000) {runCatching {compose.onNodeWithTag(tag).assertIsDisplayed()}.isSuccess}}
        fun screenshot(name:String) {
            compose.waitForIdle()
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        credential.save("p14-native-live",base,token)
        credential.markActive("p14-native-live",base)
        val turns=mutableListOf<JsonObject>()
        try {
            ActivityScenario.launch<P14EntryActivity>(Intent(context,P14EntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(P14EntryActivity.EXTRA_CONVERSATION_ID,accepted.conversationId)).use {
                awaitTag("p14-entry-body")
                compose.onNodeWithTag("p14-inbox-open-ai").performClick()
                awaitTag("p14-entry-message-input")
                for((index,question) in listOf("仅按当前引用的记录回答暗号，请只输出暗号，不调用工具。","仅根据我们刚才的对话，再说一次刚才的暗号，不读取文件，不调用工具。").withIndex()) {
                    compose.onNodeWithTag("p14-entry-message-input").performTextInput(question)
                    compose.onNodeWithTag("p14-inbox-ai-send").performClick()
                    // Wait on the durable projection AND the actual displayed assistant bubble.
                    compose.waitUntil(150_000) {
                        val count=runBlocking {repo.messages(accepted.conversationId,100).first().count {it.role=="assistant"&&it.deliveryState!="STREAMING"&&it.bodyText.contains(code)}}
                        count>=index+1 && runCatching {compose.onAllNodes(hasText(code,substring=true).and(hasAnyAncestor(hasTestTag("p14-ai-source")).not())).onLast().assertIsDisplayed()}.isSuccess
                    }
                    val history=repo.messages(accepted.conversationId,100).first()
                    val user=history.single {it.role=="user"&&it.bodyText==question}
                    val reply=history.filter {it.role=="assistant"&&it.deliveryState!="STREAMING"}.last()
                    assertTrue(history.indexOf(user)<history.indexOf(reply))
                    assertTrue(reply.bodyText.contains(code))
                    turns+=buildJsonObject {put("question",question);put("user_message_id",user.messageId);put("reply_message_id",reply.messageId);put("reply",reply.bodyText)}
                    screenshot("0${index+1}-real-ai-turn")
                }
                compose.onNodeWithTag("p14-entry-back").performClick();awaitTag("p14-entry-body")
                screenshot("03-return-detail")
                compose.onNodeWithTag("p14-entry-back").performClick();awaitTag("p14-inbox-list")
                screenshot("04-return-inbox")
            }
            assertEquals(original,repo.entry(accepted.entryId)!!.bodyText)
            assertEquals(updated,repo.message(accepted.messageId)!!.bodyText)
            assertEquals(1,db.dao().directoryPage(100,0).size)
            val history=repo.messages(accepted.conversationId,100).first()
            assertEquals(2,history.count {it.role=="assistant"&&it.deliveryState!="STREAMING"})
            assertEquals(3,history.count {it.role=="user"})
            assertTrue(history.all {it.conversationId==accepted.conversationId})
            File(output,"native-live-result.json").writeText(buildJsonObject {
                put("actual_android_activity",true);put("isolated_server",true);put("real_hermes",true)
                put("entry_id",accepted.entryId);put("conversation_id",accepted.conversationId);put("capture_message_id",accepted.messageId)
                put("original_capture",original);put("revised_source",updated);put("turns",JsonArray(turns))
                put("same_conversation",true);put("source_preserved",true);put("return_navigation",true)
            }.toString())
        } finally {
            credential.clear("p14-native-live")
            check(context.getSharedPreferences("p14-rikkahub-runtime",Context.MODE_PRIVATE).edit().commit())
        }
    }
}
