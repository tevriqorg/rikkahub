package me.rerere.rikkahub.data.remote.p14

import android.content.Intent
import android.content.Context
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
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

    /** Actual SystemUI notification click; the cold target is not pre-seeded by the test. */
    @Test fun realNotificationOpensEntry() = runBlocking {
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
        val manager=context.getSystemService(NotificationManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+10000
        while(manager.activeNotifications.none { it.id==notificationId.hashCode() } && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        assertTrue("Real durable notification must be projected",manager.activeNotifications.any { it.id==notificationId.hashCode() })
        val monitor=instrumentation.addMonitor(P14EntryActivity::class.java.name,null,false)
        var activity:P14EntryActivity?=null
        try {
            assertTrue("SystemUI notification shade must open",instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            var clicked=false
            val clickDeadline=SystemClock.elapsedRealtime()+10000
            while(!clicked && SystemClock.elapsedRealtime()<clickDeadline) {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                val matches=root?.findAccessibilityNodeInfosByText(notification.title).orEmpty()
                for(node in matches) {
                    if(node.text?.toString()!=notification.title) continue
                    var target:AccessibilityNodeInfo?=node
                    repeat(8) {
                        if(!clicked) clicked=target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                        target=target?.parent
                    }
                    if(clicked) break
                }
                if(!clicked) SystemClock.sleep(100)
            }
            assertTrue("Actual notification title must be clicked in SystemUI",clicked)
            activity=monitor.waitForActivityWithTimeout(15000) as? P14EntryActivity
            assertNotNull("Notification must open actual Entry Activity",activity)
            assertEquals(conversationId,activity!!.intent.getStringExtra(P14EntryActivity.EXTRA_CONVERSATION_ID))
            assertEquals(notificationId,activity!!.intent.getStringExtra(P14EntryActivity.EXTRA_NOTIFICATION_ID))
            val expectedBody="Synthetic task4 fixture; not generated by Hermes"
            compose.waitUntil(timeoutMillis=20000) { compose.onAllNodesWithText(expectedBody).fetchSemanticsNodes().isNotEmpty() }
            // The Entry header and its source message legitimately repeat this body.
            compose.onAllNodesWithText(expectedBody)[0].assertIsDisplayed()
            assertEquals(entryId,requireNotNull(product.conversationForId(conversationId)).entryId)
            assertEquals(cursorBefore,product.currentCursor())
            assertEquals(beforeId,bootstrap.getString("before_id",null))
            android.util.Log.i("P14_TASK4","realNotificationOpensEntry PASS: real SystemUI click opened actual cold Entry body; global replay cursor and older-page locator unchanged")
        } finally {
            activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() } }
            instrumentation.removeMonitor(monitor)
        }
    }

}
