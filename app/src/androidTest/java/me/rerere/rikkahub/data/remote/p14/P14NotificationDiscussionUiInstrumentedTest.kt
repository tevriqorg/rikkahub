package me.rerere.rikkahub.data.remote.p14

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.activity.P14InboxActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The notification itself is information, never an Agent permission grant. */
@RunWith(AndroidJUnit4::class)
class P14NotificationDiscussionUiInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun offlineNotificationCanShowDetailsButCannotApproveOrLaunchRemoteDiscussion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(
            "Synthetic-only test cannot run on a physical owner device",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
        )
        assertNull(P14RuntimeCredentialStore(context).activeConnection())

        val id = "ntf-" + UUID.randomUUID().toString().replace("-", "")
        val interactionId = "interaction-" + UUID.randomUUID().toString()
        val title = "P14 offline notification permission guard"
        val dao = P14RemoteDatabase.get(context).dao()

        runBlocking {
            dao.upsertNotifications(
                listOf(
                    P14NotificationEntity(
                        notificationId = id,
                        conversationId = null,
                        title = title,
                        body = "Context only; no automatic deploy or approval",
                        severity = "warning",
                        interactionId = interactionId,
                        state = "UNREAD",
                        revision = 1,
                        expiresAt = null,
                        createdAt = "2026-10-31T12:00:00Z",
                        updatedAt = "2026-10-31T12:00:00Z",
                    ),
                ),
            )
            dao.upsertInteraction(
                P14InteractionEntity(
                    interactionId = interactionId,
                    conversationId = "conversation-offline",
                    notificationId = id,
                    prompt = "Do you approve?",
                    optionsJson = """[{"id":"approve","label":"Approve"},{"id":"deny","label":"Deny"}]""",
                    status = "PENDING",
                    response = null,
                    actionable = true,
                ),
            )
        }

        val scenario = ActivityScenario.launch<P14InboxActivity>(
            Intent(context, P14InboxActivity::class.java)
                .putExtra("notification_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            compose.waitUntil(timeoutMillis = 20_000L) {
                compose.onAllNodes(hasText(title))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithText(title).assertIsDisplayed()
            // Deep link expands the same local durable Notification object.
            compose.onNodeWithText("通知 ID：$id").assertIsDisplayed()
            compose.onNodeWithText("与 AI 讨论").performClick()

            compose.waitUntil(timeoutMillis = 15_000L) {
                compose.onAllNodes(hasText("无法打开通知讨论：", substring = true))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithText("无法打开通知讨论：", substring = true).assertIsDisplayed()

            // Interaction continues to require an explicit authenticated
            // decision: the discussion button must never enqueue approval.
            runBlocking {
                assertEquals("PENDING", dao.interaction(interactionId)?.status)
                assertTrue(
                    dao.pendingOperations().none { it.notificationId == id && it.type == "RESPOND_INTERACTION" },
                )
            }
        } finally {
            scenario.close()
            val sql = P14RemoteDatabase.get(context).openHelper.writableDatabase
            sql.execSQL("DELETE FROM p14_interactions WHERE interaction_id=?", arrayOf(interactionId))
            sql.execSQL("DELETE FROM p14_operations WHERE notification_id=?", arrayOf(id))
            sql.execSQL("DELETE FROM p14_notifications WHERE notification_id=?", arrayOf(id))
        }
    }
}
