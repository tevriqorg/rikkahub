package me.rerere.rikkahub.data.remote.p14

import android.content.Intent
import android.content.Context
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import java.io.File
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.InputDevice
import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.ui.activity.P14EntryActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity/Compose callbacks against emulator app-private synthetic data. No sensor emulation. */
@RunWith(AndroidJUnit4::class)
class P14RecoveryUiInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun captureAndProfileUi() {
        val intent = Intent(context, P14EntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<P14EntryActivity>(intent).use {
            compose.onNodeWithText("Capture").performClick()
            compose.onNodeWithText("Mixed Capture · Save first, process later").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Text / URL / shared material").performTextReplacement("task4-ui-durable-text")
            compose.runOnIdle { assertEquals("task4-ui-durable-text", P14CaptureDraftStore(context).read().text) }
            compose.onNodeWithText("Profile").performClick()
            compose.onNodeWithTag("p14-entry-list").performScrollToIndex(0)
            compose.onNodeWithText("Profile / Capability (portable YAML; no secrets)").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Effective capabilities").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Location is off by default. No passive background collection; explicit permission and policy required.").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun sharedTextUsesDurableComposer() {
        val intent = Intent(context, P14EntryActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "task4-shared-payload")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<P14EntryActivity>(intent).use {
            compose.onNodeWithText("Mixed Capture · Save first, process later").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("task4-shared-payload").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertEquals("task4-shared-payload", P14CaptureDraftStore(context).read().text) }
        }
    }

    /** Exercise the actual Save Entry button while no runtime credential/network is available. */
    @Test fun saveCaptureOfflineFromUi():Unit = runBlocking {
        assertNull(P14RuntimeCredentialStore(context).activeConnection())
        val database=P14ProductDatabase.get(context)
        val dao=database.dao()
        val prefs=context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE)
        val originalConversation=requireNotNull(prefs.getString("offline-conversation",null))
        val originalMessages=dao.listMessages(originalConversation)
        val originalVoice=dao.attachmentsForMessage("ae800001-0000-4000-8000-000000000001")
        val text="task4-ui-saved-"+java.util.UUID.randomUUID()
        val intent=Intent(context,P14EntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<P14EntryActivity>(intent).use {
            compose.onNodeWithText("Capture").performClick()
            compose.onNodeWithText("Text / URL / shared material").performScrollTo().performTextReplacement(text)
            val draft=P14CaptureDraftStore(context).read()
            assertEquals(text,draft.text)
            assertTrue("This test preserves the existing owned synthetic file",draft.materials.isNotEmpty())
            val materialHashes=draft.materials.map { row -> captureFileHash(row.locator) }.sorted()
            assertNull("Save must create a new accepted message",dao.message(draft.messageId))
            compose.onNodeWithText("Save Entry").performScrollTo().assertIsEnabled().performClick()
            val conversation=kotlinx.coroutines.withTimeout(15000) {
                var saved=dao.conversationByExternalSession(draft.draftId)
                while(saved==null) {
                    kotlinx.coroutines.delay(50)
                    saved=dao.conversationByExternalSession(draft.draftId)
                }
                requireNotNull(saved)
            }
            assertEquals(text,requireNotNull(dao.entry(conversation.entryId)).bodyText)
            val message=requireNotNull(dao.message(draft.messageId))
            assertEquals(conversation.conversationId,message.conversationId)
            assertEquals(text,message.bodyText)
            assertEquals("LOCAL_PENDING",message.deliveryState)
            val pending=dao.pendingOutbox().single { row -> row.messageId==draft.messageId }
            assertEquals(conversation.entryId,pending.entryId)
            assertEquals(conversation.conversationId,pending.conversationId)
            assertEquals("PENDING",pending.state)
            val materials=dao.attachmentsForMessage(draft.messageId)
            assertEquals(draft.materials.size,materials.size)
            assertEquals(materialHashes,materials.map { row -> captureFileHash(row.localLocator) }.sorted())
            materials.forEach { row ->
                val file=java.io.File(requireNotNull(android.net.Uri.parse(row.localLocator).path))
                assertTrue("Accepted Capture source must be a durable nonempty file",file.isFile && file.length()>0)
            }
            awaitEntryBody(text)
            assertEquals("",P14CaptureDraftStore(context).read().text)
            assertNull(P14RuntimeCredentialStore(context).activeConnection())
            assertEquals(originalMessages,dao.listMessages(originalConversation))
            assertEquals(originalVoice,dao.attachmentsForMessage("ae800001-0000-4000-8000-000000000001"))
            assertTrue(prefs.edit().putString("ui-saved-entry",conversation.entryId)
                .putString("ui-saved-conversation",conversation.conversationId)
                .putString("ui-saved-message",draft.messageId).putString("ui-saved-operation",pending.clientOperationId)
                .putString("ui-saved-text",text).putInt("ui-saved-materials",materials.size)
                .putString("ui-saved-material-hashes",materialHashes.joinToString(",")).commit())
            android.util.Log.i("P14_TASK4","saveCaptureOfflineFromUi PASS: actual Save Entry button durably accepted mixed text/file, visible Entry and pending Outbox without credentials; original offline voice unchanged")
        }
    }

    /** The external script force-stops the application between these two methods. */
    @Test fun verifyUiSavedCapture():Unit = runBlocking {
        assertNull(P14RuntimeCredentialStore(context).activeConnection())
        val prefs=context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE)
        val entryId=requireNotNull(prefs.getString("ui-saved-entry",null))
        val conversationId=requireNotNull(prefs.getString("ui-saved-conversation",null))
        val messageId=requireNotNull(prefs.getString("ui-saved-message",null))
        val operationId=requireNotNull(prefs.getString("ui-saved-operation",null))
        val text=requireNotNull(prefs.getString("ui-saved-text",null))
        val dao=P14ProductDatabase.get(context).dao()
        assertEquals(text,requireNotNull(dao.entry(entryId)).bodyText)
        assertEquals(entryId,requireNotNull(dao.conversation(conversationId)).entryId)
        assertEquals(text,requireNotNull(dao.message(messageId)).bodyText)
        assertEquals("LOCAL_PENDING",requireNotNull(dao.message(messageId)).deliveryState)
        val pending=dao.pendingOutbox().single { row -> row.clientOperationId==operationId }
        assertEquals(messageId,pending.messageId)
        assertEquals("PENDING",pending.state)
        assertEquals(1,dao.listMessages(conversationId).count { row -> row.messageId==messageId })
        val materials=dao.attachmentsForMessage(messageId)
        assertEquals(prefs.getInt("ui-saved-materials",-1),materials.size)
        assertEquals(requireNotNull(prefs.getString("ui-saved-material-hashes",null)).split(","),
            materials.map { row -> captureFileHash(row.localLocator) }.sorted())
        materials.forEach { row ->
            val file=java.io.File(requireNotNull(android.net.Uri.parse(row.localLocator).path))
            assertTrue(file.isFile && file.length()>0)
        }
        val intent=Intent(context,P14EntryActivity::class.java)
            .putExtra(P14EntryActivity.EXTRA_CONVERSATION_ID,conversationId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<P14EntryActivity>(intent).use {
            awaitEntryBody(text)
        }
        assertNull(P14RuntimeCredentialStore(context).activeConnection())
        android.util.Log.i("P14_TASK4","verifyUiSavedCapture PASS after external force-stop: actual UI-saved Entry, message, file and stable pending operation survived and reopened visibly; no upload claimed")
    }

    private fun captureFileHash(locator:String):String {
        val file=java.io.File(requireNotNull(android.net.Uri.parse(locator).path))
        assertTrue(file.isFile && file.length()>0)
        return java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Actual SystemUI notification click; the cold target is not pre-seeded by the test. */
    @Test fun realNotificationOpensEntry():Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val notificationId=requireNotNull(args.getString("p14NotificationId"))
        val conversationId=requireNotNull(args.getString("p14MediaConversationId"))
        val entryId=requireNotNull(args.getString("p14MediaEntryId"))
        val http=OkHttpClient()
        val product=P14ProductRepository(context,http)
        val remote=P14RemoteRepository(context,GlobalContext.get().get<SettingsStore>(),http)
        remote.refreshActive()
        val notification=requireNotNull(remote.notification(notificationId))
        assertEquals(conversationId,notification.conversationId)
        assertNull("Notification target must start absent locally to exercise cold lookup",product.conversationForId(conversationId))
        val cursorBefore=product.currentCursor()
        val bootstrap=context.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE)
        val beforeId=bootstrap.getString("before_id",null)
        val beforeTime=bootstrap.getString("before_time",null)
        val beforeMore=bootstrap.getBoolean("more",false)
        val manager=context.getSystemService(NotificationManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+10000
        while(manager.activeNotifications.none { it.id==notificationId.hashCode() } && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        assertTrue("Real durable notification must be projected",manager.activeNotifications.any { it.id==notificationId.hashCode() })
        val monitor=instrumentation.addMonitor(P14EntryActivity::class.java.name,null,false)
        var activity:P14EntryActivity?=null
        val originalAutomationFlags=instrumentation.uiAutomation.serviceInfo.flags
        try {
            val automation=instrumentation.uiAutomation
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            assertTrue("SystemUI notification shade must open",automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            var clicked=false
            val clickDeadline=SystemClock.elapsedRealtime()+10000
            while(!clicked && SystemClock.elapsedRealtime()<clickDeadline) {
                val matches=systemUiNodes().filter { it.text?.toString()==notification.title && it.isVisibleToUser }
                for(node in matches) {
                    if(node.text?.toString()!=notification.title) continue
                    // Real touch at the bounds of the exact visible title; no PendingIntent dispatch.
                    val bounds=Rect()
                    node.getBoundsInScreen(bounds)
                    if(!bounds.isEmpty) {
                        val down=SystemClock.uptimeMillis()
                        val press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0)
                        val release=MotionEvent.obtain(down,down+50,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0)
                        press.source=InputDevice.SOURCE_TOUCHSCREEN;release.source=InputDevice.SOURCE_TOUCHSCREEN
                        try {
                            clicked=automation.injectInputEvent(press,true) && automation.injectInputEvent(release,true)
                        } finally { press.recycle();release.recycle() }
                    }
                    if(clicked) break
                }
                if(!clicked) SystemClock.sleep(100)
            }
            if(!clicked) saveSystemUiTree("task4-notification-systemui-tree.txt")
            assertTrue("Actual notification title must be clicked in SystemUI",clicked)
            activity=monitor.waitForActivityWithTimeout(15000) as? P14EntryActivity
            assertNotNull("Notification must open actual Entry Activity",activity)
            assertEquals(conversationId,activity!!.intent.getStringExtra(P14EntryActivity.EXTRA_CONVERSATION_ID))
            assertEquals(notificationId,activity!!.intent.getStringExtra(P14EntryActivity.EXTRA_NOTIFICATION_ID))
            val expectedBody="Synthetic task4 fixture; not generated by Hermes"
            awaitEntryBody(expectedBody)
            assertEquals(entryId,requireNotNull(product.conversationForId(conversationId)).entryId)
            val cursorAfter=product.currentCursor()
            assertTrue("Cold navigation must not regress replay",cursorAfter>=cursorBefore)
            if(cursorAfter>cursorBefore) {
                val runtime=requireNotNull(P14RuntimeCredentialStore(context).activeConnection())
                require(runtime.baseUrl=="http://10.0.2.2:8014/v1")
                var after=cursorBefore
                while(after<cursorAfter) {
                    val request=Request.Builder().url(runtime.baseUrl+"/sync/events?after=$after&limit=100")
                        .header("Authorization","Bearer "+runtime.bearerToken).build()
                    val events=http.newCall(request).execute().use { response ->
                        assertEquals(200,response.code)
                        Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject["events"]!!.jsonArray
                            .map { it.jsonObject }.filter { it["sync_seq"]!!.jsonPrimitive.long<=cursorAfter }
                    }
                    assertTrue("Cursor advancement needs authoritative replay evidence",events.isNotEmpty())
                    for(event in events) {
                        val seq=event["sync_seq"]!!.jsonPrimitive.long
                        assertTrue(seq>after)
                        assertEquals("notification.state.updated",event["event_type"]!!.jsonPrimitive.content)
                        assertEquals(notificationId,event["entity_id"]!!.jsonPrimitive.content)
                        after=seq
                    }
                }
                assertEquals(cursorAfter,after)
            }
            assertEquals(beforeId,bootstrap.getString("before_id",null))
            assertEquals(beforeTime,bootstrap.getString("before_time",null))
            assertEquals(beforeMore,bootstrap.getBoolean("more",false))
            kotlinx.coroutines.withTimeout(15000) {
                while(remote.notification(notificationId)?.state!="OPENED") {
                    assertTrue(remote.processPendingActive())
                    kotlinx.coroutines.delay(100)
                }
            }
            assertEquals("OPENED",remote.notification(notificationId)?.state)
            android.util.Log.i("P14_TASK4","realNotificationOpensEntry PASS: real SystemUI click opened actual cold Entry body; older-page locator unchanged; any cursor advancement matched actual authoritative notification-state events")
        } finally {
            activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
            instrumentation.removeMonitor(monitor)
            instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply { flags=originalAutomationFlags }
        }
    }

    private fun systemUiRoots():List<AccessibilityNodeInfo> {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        return (automation.windows.mapNotNull { it.root }+listOfNotNull(automation.rootInActiveWindow))
            .filter { it.packageName?.toString()=="com.android.systemui" }
    }
    private fun systemUiNodes():List<AccessibilityNodeInfo> {
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
        systemUiRoots().distinctBy { it.windowId }.forEach { queue.add(it) }
        val nodes=mutableListOf<AccessibilityNodeInfo>()
        while(queue.isNotEmpty() && nodes.size<600) {
            val node=queue.removeFirst();nodes.add(node)
            for(i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return nodes
    }
    private fun saveSystemUiTree(name:String) {
        val lines=mutableListOf<String>()
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
        systemUiRoots().forEach { queue.add(it) }
        while(queue.isNotEmpty() && lines.size<600) {
            val node=queue.removeFirst()
            lines.add("id=${node.viewIdResourceName} class=${node.className} clickable=${node.isClickable} visible=${node.isVisibleToUser} text=${node.text?.take(160)} desc=${node.contentDescription?.take(160)}")
            for(i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        File(context.filesDir,name).writeText(lines.joinToString("\n"))
    }

    private fun awaitEntryBody(text:String) {
        compose.waitUntil(timeoutMillis=20000) {
            compose.onAllNodes(hasTestTag("p14-entry-body") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("p14-entry-body").assertTextEquals(text).assertIsDisplayed()
    }

}
