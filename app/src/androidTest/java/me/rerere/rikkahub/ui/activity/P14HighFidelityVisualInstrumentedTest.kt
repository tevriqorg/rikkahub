package me.rerere.rikkahub.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
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

/** Real production Activity/Room/Outbox, only on an explicitly selected disposable emulator. */
@RunWith(AndroidJUnit4::class)
class P14HighFidelityVisualInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val db get()=P14ProductDatabase.get(context)
    private val repo get()=P14ProductRepository(context,OkHttpClient())
    private val prefs get()=context.getSharedPreferences("p14-visual-synthetic",Context.MODE_PRIVATE)
    private val output:File get() {
        val root=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        return File(context.getExternalFilesDir(null),"ci-additional/p14-high-fidelity").apply {check(mkdirs() || isDirectory) {"Screenshot output directory unavailable: $absolutePath"}}
    }
    private fun guard() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("p14SyntheticFixture"))
        assertTrue("Visual fixture must never run on a physical/production device",Build.HARDWARE in setOf("ranchu","goldfish")||Build.FINGERPRINT.contains("generic"))
        assertNull("Synthetic journey must not possess an active Server credential",P14RuntimeCredentialStore(context).activeConnection())
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun shell(command:String):String=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    private data class Ime(val shown:Boolean,val bottom:Int,val width:Int,val height:Int)
    private fun ime():Ime {
        var result=Ime(false,0,0,0)
        instrumentation.runOnMainSync {
            val views=WindowInspector.getGlobalWindowViews()
            val native=views.lastOrNull {it.hasWindowFocus()}?.rootWindowInsets ?: views.mapNotNull {it.rootWindowInsets}.maxByOrNull {it.getInsets(WindowInsets.Type.ime()).bottom}
            val metrics=context.resources.displayMetrics
            result=Ime(native?.isVisible(WindowInsets.Type.ime())==true,native?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0,metrics.widthPixels,metrics.heightPixels)
        };return result
    }
    private fun awaitIme(shown:Boolean) {compose.waitUntil(20_000) {val v=ime();v.shown==shown&&(!shown||v.bottom>100)}}
    private fun keyboard(show:Boolean) {instrumentation.runOnMainSync {WindowInspector.getGlobalWindowViews().lastOrNull {it.hasWindowFocus()}?.windowInsetsController?.let {if(show)it.show(WindowInsets.Type.ime()) else it.hide(WindowInsets.Type.ime())}};awaitIme(show)}
    private fun capture(name:String,keyboardRequired:Boolean=false,toolbar:String?=null) {
        compose.waitForIdle();if(keyboardRequired)awaitIme(true)
        var stableSince=0L
        var lastGeometry=""
        if(keyboardRequired&&toolbar!=null)compose.waitUntil(20_000) {
            val state=ime()
            val node=compose.onAllNodesWithTag(toolbar).fetchSemanticsNodes(atLeastOneRootRequired=false).singleOrNull()
            // Reading lazy node geometry may synchronously measure Compose. Keep it on UI,
            // just like the AndroidX semantics selector; never measure on the runner thread.
            val bounds=compose.runOnUiThread {node?.boundsInWindow}
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            val height=bitmap?.height;bitmap?.recycle()
            val valid=state.shown&&state.bottom>100&&bounds!=null&&height!=null&&
                kotlin.math.abs(bounds.bottom-(height-state.bottom))<context.resources.displayMetrics.density*16
            val geometry="${state.width}:${state.height}:${state.bottom}:${bounds}"
            val now=SystemClock.uptimeMillis()
            if(!valid||geometry!=lastGeometry){stableSince=now;lastGeometry=geometry}
            valid&&now-stableSince>=600
        }
        val native=ime();val screenshot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output,"$name.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
        val toolbarBottom=toolbar?.let {
            val node=compose.onNodeWithTag(it).fetchSemanticsNode()
            compose.runOnUiThread {node.boundsInWindow.bottom}
        }
        val keyboardTop=screenshot.height-native.bottom
        if(keyboardRequired) {
            assertTrue("Real Android IME must be visible",native.shown&&native.bottom>100)
            if(toolbarBottom!=null)assertTrue("Toolbar/panel must touch the actual keyboard: bottom=$toolbarBottom keyboardTop=$keyboardTop",kotlin.math.abs(toolbarBottom-keyboardTop)<context.resources.displayMetrics.density*16)
        }
        File(output,"$name.json").writeText(buildJsonObject {
            put("fixture","synthetic-only-production-activity");put("screen_width_px",screenshot.width);put("screen_height_px",screenshot.height)
            put("density",context.resources.displayMetrics.density);put("font_scale",context.resources.configuration.fontScale)
            put("orientation",context.resources.configuration.orientation);put("dark",context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES)
            put("ime_visible",native.shown);put("ime_bottom_px",native.bottom);put("ime_top_px",keyboardTop)
            toolbarBottom?.let {put("toolbar_bottom_px",it)};put("bitmap_sha256",sha(File(output,"$name.png").readBytes()))
        }.toString())
    }
    private fun launch(conversationId:String?=null):ActivityScenario<P14EntryActivity> {
        val intent=Intent(context,P14EntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        conversationId?.let {intent.putExtra(P14EntryActivity.EXTRA_CONVERSATION_ID,it)}
        return ActivityScenario.launch(intent)
    }
    private fun awaitTag(tag:String) {compose.waitUntil(20_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()&&runCatching {compose.onNodeWithTag(tag).assertIsDisplayed()}.isSuccess};compose.onNodeWithTag(tag).assertIsDisplayed()}
    private fun enterEditor() {
        awaitTag("p14-entry-body")
        compose.waitUntil(15_000){compose.onAllNodes(hasTestTag("p14-entry-body").and(hasClickAction())).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.onNodeWithTag("p14-entry-body").performClick();awaitTag("p14-entry-editor")
    }
    private fun photo(name:String,food:Boolean=false):File {
        val root=File(context.filesDir,"p14-synthetic-visual").apply {mkdirs()};val file=File(root,name)
        val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(AndroidColor.rgb(233,221,198));paint.color=AndroidColor.rgb(91,61,37);canvas.drawRect(45f,60f,755f,550f,paint)
        paint.color=AndroidColor.rgb(202,171,118);canvas.drawRect(70f,90f,730f,165f,paint)
        paint.color=AndroidColor.rgb(237,206,142);for(i in 0..3)canvas.drawRect(85f+i*165,190f,220f+i*165,505f,paint)
        paint.color=AndroidColor.rgb(83,99,73);for(i in 0..2)canvas.drawRect(100f+i*240,255f,210f+i*240,395f,paint)
        paint.color=AndroidColor.rgb(56,43,34);paint.textSize=40f;canvas.drawText("SYNTHETIC LOCAL SHOP",105f,142f,paint)
        if(food){paint.color=AndroidColor.WHITE;canvas.drawCircle(400f,340f,180f,paint);paint.color=AndroidColor.rgb(150,91,37);canvas.drawCircle(400f,340f,135f,paint);paint.color=AndroidColor.rgb(70,121,60);for(i in 0..6)canvas.drawCircle(340f+i*19,300f+(i%2)*60,19f,paint)}
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();return file
    }
    private fun pdf():File {
        val file=File(context.filesDir,"p14-synthetic-visual/小店观察笔记.pdf")
        val pdf=PdfDocument();val page=pdf.startPage(PdfDocument.PageInfo.Builder(480,640,1).create());val paint=Paint().apply {textSize=23f};page.canvas.drawText("P14 SYNTHETIC PDF ATTACHMENT",30f,70f,paint);pdf.finishPage(page);file.outputStream().use {pdf.writeTo(it)};pdf.close();return file
    }
    private fun voice():File {
        val data=ByteArray(32000);val buffer=java.nio.ByteBuffer.allocate(44+data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+data.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size).put(data)
        return File(context.filesDir,"p14-synthetic-visual/语音观察.wav").apply {writeBytes(buffer.array())}
    }
    private suspend fun seed():List<P14AcceptedMutation> {
        guard();db.clearAllTables();P14CaptureDraftStore(context).clearAfterAccept()
        val shop=photo("小店门面.png");val books=photo("社区书架.png");val meal=photo("地方早餐.png",true);val document=pdf();val audio=voice()
        val contents=listOf(
            "关于日本地方小店的经营模式\n今天在旭川看到一家小店，虽然规模不大，但通过本地食材和社区运营做得很好。\n\n有几个值得思考的点：\n1. 不依赖大平台评分，靠社区口碑。\n2. 强调本地特色和真实体验。\n3. 与周边社区建立长期关系。",
            "Publisher 的新设计思路\n希望收集能先保存，再决定是否与 AI 讨论。每条记录独立存在。",
            "日本酒店的本地文化体验\n旭川酒店的早餐与地方文化结合得很好，值得专门记录下来。",
            "周末健身计划\n重新开始每周三次力量训练，逐步增加步行距离。",
            "阅读《金字塔原理》笔记\n第三章：结构化思维的底层场景与表达方式。",
            "AIBOX 知识整理\n整理日本商业分析资料，并保留可靠的原始来源。",
            "PDF 资料：小店观察\n一份可以独立查看、分享与归档的附件资料。",
            "语音观察\n原始音频是独立附件，不能被一段转写文字替代。",
            "长文本记录\n"+(1..45).joinToString("\n"){"第 $it 段：内容应完整保留；滚动和编辑不能改变原始 Capture。"},
            "多行标题与正文摘要的显示验证，观察紧凑列表在较长内容下的自然换行\n第一行摘要，记录重要的现场细节。\n第二行摘要，保持信息层次。",
            "分类与置顶的本地整理\n同一记录可以加入多个已有视图，离线时也先持久保存。",
            "摩托\n具体",
            "已归档的观察\n这是一条归档记录，不是一个已完成的任务。",
        )
        val accepted=mutableListOf<P14AcceptedMutation>()
        for((i,text) in contents.withIndex()) {
            val id=Uuid.parse("b1400030-0000-4000-8000-"+(i+1).toString().padStart(12,'0'))
            val parts=mutableListOf<UIMessagePart>(UIMessagePart.Text(text))
            if(i==0){parts+=UIMessagePart.Image(Uri.fromFile(shop).toString());parts+=UIMessagePart.Image(Uri.fromFile(books).toString());parts+=UIMessagePart.Document(Uri.fromFile(document).toString(),document.name,"application/pdf")}
            if(i==2)parts+=UIMessagePart.Image(Uri.fromFile(meal).toString())
            if(i==6)parts+=UIMessagePart.Document(Uri.fromFile(document).toString(),document.name,"application/pdf")
            if(i==7)parts+=UIMessagePart.Audio(Uri.fromFile(audio).toString(),buildJsonObject {put("p14_media_role","voice_message");put("p14_duration_ms",2000);put("p14_mime_type","audio/wav")})
            val views=when(i){0->setOf("research");1,2->setOf("idea");3->setOf("todo");5,10->setOf("execute");else->emptySet()}
            val result=repo.acceptRikkaMessage("p14-visual-$i",id.toString(),UIMessage(id=id,role=MessageRole.USER,parts=parts),null,null,true,views)
            val at=System.currentTimeMillis()-(i+1)*600_000L
            db.dao().upsertEntry(requireNotNull(repo.entry(result.entryId)).copy(createdAtEpochMs=at))
            db.dao().upsertConversation(requireNotNull(repo.conversation(result.conversationId)).copy(createdAtEpochMs=at,pinned=i<2,archived=i==12))
            db.dao().upsertMessage(requireNotNull(repo.message(result.messageId)).copy(createdAtEpochMs=at,deliveryState="SYNCED"))
            db.dao().markOutboxAcked(result.clientOperationId,System.currentTimeMillis());accepted+=result
        }
        val main=accepted[0];val now=System.currentTimeMillis()
        db.dao().upsertMessage(P14ProductMessageEntity("b1400030-1000-4000-8000-000000000001",main.conversationId,"user","这家小店的模式有什么值得深入研究的地方？",deliveryState="SYNCED",createdAtEpochMs=now-60_000))
        db.dao().upsertMessage(P14ProductMessageEntity("b1400030-1000-4000-8000-000000000002",main.conversationId,"assistant","这是一条很有价值的观察，可以从三个方向继续研究：\n\n1. **本地化差异供给**\n如何利用本地食材建立独特价值。\n\n2. **社区关系建设**\n如何通过日常互动建立长期关系。\n\n3. **可持续经营模式**\n观察复购和产品组合。",deliveryState="SYNCED",createdAtEpochMs=now-30_000))
        prefs.edit().putString("primary",main.conversationId).putString("editor",accepted[11].conversationId).putString("editor-entry",accepted[11].entryId).putString("editor-source",accepted[11].messageId).putString("voice",accepted[7].conversationId).commit()
        val manifest=buildJsonObject {
            put("synthetic",true)
            put("entry_count",accepted.size)
            put("inbox_count",12)
            put("archived_count",1)
            put("records",buildJsonArray {
                accepted.forEachIndexed { i,a ->
                    add(buildJsonObject {
                        put("entry_id",a.entryId)
                        put("conversation_id",a.conversationId)
                        put("title",contents[i].substringBefore('\n'))
                    })
                }
            })
            put("attachments",buildJsonArray {
                listOf(shop,books,meal,document,audio).forEach { file ->
                    add(buildJsonObject {
                        put("file_name",file.name)
                        put("byte_size",file.length())
                        put("sha256",sha(file.readBytes()))
                    })
                }
            })
        }
        File(output,"fixture.json").writeText(manifest.toString())
        return accepted
    }
    @Test fun visualJourney():Unit=runBlocking {
        guard();val entries=seed()
        launch().use {scenario->
            awaitTag("p14-inbox-list");compose.onNodeWithText("关于日本地方小店的经营模式").assertIsDisplayed();capture("01-inbox-light")
            compose.onNodeWithTag("p14-inbox-add").performClick();awaitTag("p14-inbox-quick-input");awaitIme(true)
            compose.onNodeWithTag("p14-inbox-quick-input").assertIsFocused();capture("02-quick-add-ime",true,"p14-quick-sheet")
            val text="键盘快速记录\n连续输入与草稿恢复"
            compose.onNodeWithTag("p14-inbox-quick-input").performTextInput("键盘快速记录")
            compose.onNodeWithTag("p14-inbox-quick-input").performTextInput("\n连续输入与草稿恢复")
            assertEquals(text,P14CaptureDraftStore(context).read().text)
            keyboard(false);capture("02b-quick-add-hidden");compose.onNodeWithTag("p14-inbox-quick-input").performClick();keyboard(true)
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(20_000){context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE};awaitTag("p14-inbox-quick-input");awaitIme(true);capture("02c-quick-add-landscape-ime",true,"p14-quick-sheet")
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(20_000){context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT};awaitTag("p14-inbox-quick-input");compose.onNodeWithTag("p14-inbox-quick-input").assertTextEquals(text)
            val captureDraft=P14CaptureDraftStore(context).read();compose.onNodeWithTag("p14-inbox-quick-save").performClick()
            compose.waitUntil(20_000){compose.onAllNodesWithTag("p14-quick-sheet").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()}
            val saved=requireNotNull(db.dao().conversationByExternalSession(captureDraft.draftId));assertEquals(text,repo.entry(saved.entryId)!!.bodyText)
            assertEquals(1,db.dao().pendingOutbox().count {it.messageId==captureDraft.messageId})
            assertTrue(db.dao().pendingOutbox().single {it.messageId==captureDraft.messageId}.payloadJson.contains("defer_ai"))
            compose.onNodeWithTag("p14-inbox-list").performScrollToNode(hasText("摩托"));compose.onNodeWithText("摩托").performClick();enterEditor();awaitIme(true)
            capture("03-editor-ime",true,"p14-editor-toolbar");compose.onNodeWithTag("p14-editor-body").performTextReplacement("具体\n新增正文与恢复验证")
            compose.onNodeWithContentDescription("撤销").performClick();compose.onNodeWithTag("p14-editor-body").assertTextEquals("具体")
            compose.onNodeWithContentDescription("重做").performClick();compose.onNodeWithTag("p14-editor-body").assertTextEquals("具体\n新增正文与恢复验证")
            val entryId=entries[11].entryId;val draftBefore=P14EntryEditDraftStore(context).read(entryId)!!
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(20_000){context.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE};awaitTag("p14-entry-editor");awaitIme(true);capture("03b-editor-landscape-ime",true,"p14-editor-toolbar")
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};compose.waitUntil(20_000){context.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT};awaitTag("p14-entry-editor")
            compose.onNodeWithTag("p14-editor-body").assertTextEquals(draftBefore.snapshot.body)
            assertEquals(draftBefore.snapshot.bodyStart,P14EntryEditDraftStore(context).read(entryId)!!.snapshot.bodyStart)
            compose.onNodeWithTag("p14-editor-back").performClick();awaitTag("p14-entry-body")
            assertEquals("摩托\n具体",repo.entry(entryId)!!.bodyText);val revised=repo.message(entries[11].messageId)!!;assertEquals(2,revised.revision)
            compose.onNodeWithTag("p14-inbox-open-ai").performClick();awaitTag("p14-entry-message-input");compose.onNodeWithTag("p14-entry-message-input").performTextInput("请基于最新修订讨论")
            compose.onNodeWithTag("p14-inbox-ai-send").performClick();compose.waitForIdle()
            val turn=kotlinx.coroutines.withTimeout(15_000) {
                var found=db.dao().pendingOutbox().lastOrNull {it.conversationId==entries[11].conversationId&&it.operationType=="SEND_MESSAGE"}
                while(found==null){kotlinx.coroutines.delay(50);found=db.dao().pendingOutbox().lastOrNull {it.conversationId==entries[11].conversationId&&it.operationType=="SEND_MESSAGE"}}
                requireNotNull(found)
            };val payload=Json.parseToJsonElement(turn.payloadJson).jsonObject
            assertEquals(entries[11].messageId,payload["reply_to_message_id"]!!.jsonPrimitive.content);assertEquals(revised.bodyText,payload["quote"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            shell("input keyevent KEYCODE_BACK");awaitIme(false);SystemClock.sleep(500);shell("input keyevent KEYCODE_BACK");awaitTag("p14-entry-body");compose.onNodeWithTag("p14-entry-back").performClick();awaitTag("p14-inbox-list")
            compose.onNodeWithTag("p14-inbox-list").performScrollToIndex(0);compose.onNodeWithText("关于日本地方小店的经营模式").performClick();awaitTag("p14-entry-body");capture("04-detail-light")
            compose.onNodeWithTag("p14-inbox-open-ai").performClick();awaitTag("p14-entry-message-input");capture("05-ai-light")
            compose.onNodeWithTag("p14-entry-back").performClick();awaitTag("p14-entry-body");compose.onNodeWithTag("p14-entry-back").performClick();awaitTag("p14-inbox-list")
            shell("cmd uimode night yes");compose.waitUntil(20_000){context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES};awaitTag("p14-inbox-list");capture("06-inbox-dark")
            compose.onNodeWithTag("p14-inbox-add").performClick();awaitTag("p14-inbox-quick-input");awaitIme(true);capture("06b-quick-add-dark-ime",true,"p14-quick-sheet")
            keyboard(false)
        }
        shell("cmd uimode night no")
        // Production Activity, Room, Outbox and real HTTP; loopback only, never Hermes.
        // Fixtures represent existing uploaded files; preserve local bytes for handoff tests.
        entries.forEach { entry -> db.dao().attachmentsForMessage(entry.messageId).forEach { attachment ->
            db.dao().setAttachmentState(attachment.localAttachmentId,"SYNCED","synthetic-"+attachment.localAttachmentId)
        } }
        val target=entries[0].conversationId
        repo.acceptEntryRevision(target,"地方小店讨论记录\n编辑后的上下文内容",1)
        val credential=P14RuntimeCredentialStore(context)
        P14SyntheticChatServer(target).use { server ->
            credential.save("synthetic-native-chat",server.baseUrl,"synthetic-loopback-only")
            credential.markActive("synthetic-native-chat",server.baseUrl)
            try {launch(target).use {
                awaitTag("p14-entry-body");compose.onNodeWithTag("p14-inbox-open-ai").performClick();awaitTag("p14-entry-message-input")
                for(question in listOf("今天下午怎么去东京站？","然后怎么回来？")) {
                    compose.onNodeWithTag("p14-entry-message-input").performTextInput(question)
                    compose.onNodeWithTag("p14-inbox-ai-send").performClick()
                    compose.waitUntil(30_000){compose.onAllNodesWithText("正在组织合成回复…",substring=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
                    compose.waitUntil(45_000){compose.onAllNodesWithText("合成回复：$question",substring=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
                }
                keyboard(false);capture("09-native-chat-exchange")
                assertEquals(2,server.targetTurns);assertEquals(2,server.targetMessageIds.size);assertTrue(server.retries>=3)
                val history=repo.messages(target,100).first()
                assertFalse(history.any {it.deliveryState=="STREAMING"})
                for(question in listOf("今天下午怎么去东京站？","然后怎么回来？")) {
                    val userAt=history.indexOfFirst {it.role=="user"&&it.bodyText==question}
                    val replyAt=history.indexOfFirst {it.role=="assistant"&&it.bodyText=="合成回复：$question"}
                    assertTrue("Question must precede its reply even when timestamps tie",userAt>=0&&replyAt>userAt)
                }
                File(output,"native-chat-exchange.json").writeText(buildJsonObject {
                    put("real_activity",true);put("loopback_http",true);put("first_send_503",true);put("same_message_retry",true);put("two_turns",true);put("delayed_draft_and_final",true);put("same_conversation",true);put("hermes_invoked",false)
                }.toString())
            }} finally {
                credential.clear("synthetic-native-chat")
                // The next method is a separate instrumentation process. Flush the
                // preceding asynchronous removal before this runner can terminate.
                check(context.getSharedPreferences("p14-rikkahub-runtime",Context.MODE_PRIVATE).edit().commit())
            }
        }
        assertNull(P14RuntimeCredentialStore(context).activeConnection())
        assertEquals(14,db.dao().directoryPage(100,0).size)
        File(output,"journey-result.json").writeText("""{"visual_screens":6,"real_ime_verified":true,"rotation_verified":true,"source_preserved":true,"same_conversation":true,"hermes_invoked":false,"entry_count":14}""")
    }
    @Test fun seedEditRecovery():Unit=runBlocking {
        guard();val conversation=requireNotNull(prefs.getString("editor",null));val entryId=requireNotNull(prefs.getString("editor-entry",null));val source=requireNotNull(prefs.getString("editor-source",null))
        launch(conversation).use {
            enterEditor();awaitTag("p14-editor-body");awaitIme(true)
            compose.onNodeWithTag("p14-editor-body").performTextReplacement("尚未提交的编辑草稿\n强制结束 App 后仍应保留")
            compose.onNodeWithTag("p14-editor-body").performSemanticsAction(SemanticsActions.SetSelection){it(4,4,false)}
            val draft=P14EntryEditDraftStore(context).read(entryId)!!;assertEquals(4,draft.snapshot.bodyStart)
            prefs.edit().putString("expected-draft",Json.encodeToString(draft)).putString("before-source",repo.message(source)!!.bodyText).putInt("before-ops",db.dao().pendingOutbox().count {it.conversationId==conversation&&it.operationType=="EDIT_SOURCE_MESSAGE"}).commit()
        }
    }
    @Test fun verifyEditRecovery():Unit=runBlocking {
        guard();val conversation=requireNotNull(prefs.getString("editor",null));val entryId=requireNotNull(prefs.getString("editor-entry",null));val source=requireNotNull(prefs.getString("editor-source",null))
        val expected=Json.decodeFromString<P14EntryEditDraft>(requireNotNull(prefs.getString("expected-draft",null)))
        assertEquals(expected,P14EntryEditDraftStore(context).read(entryId));assertEquals(prefs.getString("before-source",null),repo.message(source)!!.bodyText)
        val before=prefs.getInt("before-ops",-1)
        launch(conversation).use {
            enterEditor();awaitTag("p14-editor-body");awaitIme(true)
            compose.onNodeWithTag("p14-editor-body").assertTextEquals(expected.snapshot.body);capture("08-editor-recovered-ime",true,"p14-editor-toolbar")
            assertEquals(expected.snapshot.bodyStart,P14EntryEditDraftStore(context).read(entryId)!!.snapshot.bodyStart)
            compose.onNodeWithTag("p14-editor-back").performClick();awaitTag("p14-entry-body")
        }
        assertEquals("摩托\n具体",repo.entry(entryId)!!.bodyText);assertEquals(expected.text,repo.message(source)!!.bodyText)
        assertEquals(before+1,db.dao().pendingOutbox().count {it.conversationId==conversation&&it.operationType=="EDIT_SOURCE_MESSAGE"})
        File(output,"edit-recovery.json").writeText("""{"external_force_stop_required":true,"draft_text_restored":true,"selection_restored":true,"same_entry":true,"original_capture_preserved":true,"duplicate_edit":false}""")
    }
    @Test fun attachmentHandoff():Unit=runBlocking {
        guard()
        val tested=mutableListOf<JsonObject>()
        val primary=requireNotNull(prefs.getString("primary",null));val voice=requireNotNull(prefs.getString("voice",null))
        val targets=listOf(primary to "image/png",primary to "application/pdf",voice to "audio/wav")
        for((conversation,mime) in targets) {
            val source=requireNotNull(repo.ensureEntrySource(conversation))
            val attachment=db.dao().attachmentsForMessage(source.messageId).first {it.mimeType==mime}
            val file=File(requireNotNull(Uri.parse(attachment.localLocator).path));val expected=sha(file.readBytes())
            var received:JsonObject?=null
            val monitor=object:android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent:Intent):android.app.Instrumentation.ActivityResult? {
                    if(intent.action!=Intent.ACTION_VIEW)return null
                    val uri=requireNotNull(intent.data);val bytes=context.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
                    received=buildJsonObject {put("mime",intent.type.orEmpty());put("scheme",uri.scheme.orEmpty());put("sha256",sha(bytes));put("read_grant",intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)}
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                launch(conversation).use {
                    awaitTag("p14-entry-body")
                    compose.onNodeWithTag("p14-entry-attachment-${attachment.localAttachmentId}").performScrollTo().performClick()
                    compose.waitUntil(15_000){received!=null}
                    assertEquals(mime,received!!["mime"]!!.jsonPrimitive.content)
                    assertEquals("content",received!!["scheme"]!!.jsonPrimitive.content)
                    assertEquals(expected,received!!["sha256"]!!.jsonPrimitive.content)
                    assertTrue(received!!["read_grant"]!!.jsonPrimitive.boolean)
                    tested+=received!!
                }
            } finally {instrumentation.removeMonitor(monitor)}
        }
        File(output,"attachment-handoff.json").writeText(buildJsonObject {put("synthetic",true);put("external_viewer_boundary_intercepted",true);put("files",JsonArray(tested))}.toString())
    }

}
