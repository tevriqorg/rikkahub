package me.rerere.rikkahub.ui.activity

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBack
import java.io.File
import me.rerere.rikkahub.data.remote.p14.P14ProductMessageEntity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.remote.p14.P14InboxRow
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic, offline Inbox reproduction. Does not access user data, P14 Server or Hermes. */
@RunWith(AndroidJUnit4::class)
class P14InboxReplicaUiInstrumentedTest {
    @get:Rule val compose=createComposeRule()
    private val entry=P14InboxRow(
        entryId="synthetic-1",conversationId="conversation-synthetic-1",
        bodyText="北海道旅行计划\n预订札幌酒店",createdAtEpochMs=1_790_000_000_000L,
        pinned=false,archived=false,
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder = if (output != null) File(output, "p14-inbox-evidence") else File(context.getExternalFilesDir(null), "p14-inbox-evidence")
        folder.mkdirs()
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun synchronizedClockSkewDoesNotPutReplyBeforeQuestion() = kotlinx.coroutines.runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "p14-clock-" + java.util.UUID.randomUUID()
        val names = mutableSetOf<String>()
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val scoped = "$prefix-$name"
                names.add(scoped)
                return base.getSharedPreferences(scoped, mode)
            }
        }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context,
            me.rerere.rikkahub.data.remote.p14.P14ProductDatabase::class.java).build()
        try {
            val repo = me.rerere.rikkahub.data.remote.p14.P14ProductRepository(context, okhttp3.OkHttpClient(), db)
            val serverTime = java.time.Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
            // Phone is five minutes ahead. Exercise real Room upserts and event acknowledgement.
            for (state in listOf("LOCAL_PENDING", "SYNCED")) {
                db.clearAllTables()
                db.dao().upsertMessage(me.rerere.rikkahub.data.remote.p14.P14ProductMessageEntity(
                    "question", "conv-clock", "user", "Which model?",
                    deliveryState = state, createdAtEpochMs = serverTime + 300_000L))
                repo.applyLiveEvents("""{"type":"events","events":[
                  {"event_id":"q-event","sync_seq":1,"event_type":"conversation.message.created",
                   "entity_type":"message","entity_id":"question","occurred_at":"2026-10-29T12:00:00Z",
                   "payload":{"message":{"message_id":"question","conversation_id":"conv-clock","role":"user","body_text":"Which model?","created_at":"2026-10-29T12:00:00Z"}}},
                  {"event_id":"a-event","sync_seq":2,"event_type":"conversation.message.created",
                   "entity_type":"message","entity_id":"answer","occurred_at":"2026-10-29T12:00:01Z",
                   "payload":{"message":{"message_id":"answer","conversation_id":"conv-clock","role":"assistant","body_text":"Synthetic answer","created_at":"2026-10-29T12:00:01Z"}}}
                ]}""")
                assertEquals(serverTime, repo.message("question")!!.createdAtEpochMs)
                assertEquals(listOf("question", "answer"), db.dao().listMessages("conv-clock").map { it.messageId })
                assertEquals(2L, repo.currentCursor())
            }
        } finally {
            db.close()
            names.forEach { base.deleteSharedPreferences(it) }
        }
    }

    @Test fun inboxShowsRealEntryAndOpensExistingConversation() {
        var chosen:String?=null
        compose.setContent {
            RikkahubTheme {
                P14TickTickInboxScreen(
                    rows=listOf(entry),archived=false,onShowArchived={},
                    draftText="",onDraftChange={},saving=false,status=null,onQuickSave={},
                    onRichCapture={},onOpen={chosen=it},onToggleArchive={},
                    onMore={},onLoadMore={},hasMore=false,onBack={},
                )
            }
        }
        compose.onNodeWithTag("p14-inbox-list").assertIsDisplayed()
        compose.onNodeWithText("归档").assertIsDisplayed()
        compose.onNodeWithText("北海道旅行计划").assertIsDisplayed()
        screenshot("01-inbox-light")
        compose.onNodeWithText("北海道旅行计划").performClick()
        compose.runOnIdle { assertEquals("conversation-synthetic-1",chosen) }
    }

    @Test fun plusOpensTickTickStyleQuickCapture() {
        var draft=""
        compose.setContent {
            RikkahubTheme {
                P14TickTickInboxScreen(
                    rows=emptyList(),archived=false,onShowArchived={},
                    draftText=draft,onDraftChange={draft=it},saving=false,status=null,onQuickSave={},
                    onRichCapture={},onOpen={},onToggleArchive={},onMore={},onLoadMore={},
                    hasMore=false,onBack={},
                )
            }
        }
        compose.onNodeWithTag("p14-inbox-add").performClick()
        compose.onNodeWithTag("p14-inbox-quick-input").performTextInput("记录一个新想法")
        compose.runOnIdle { assertTrue(draft.contains("记录")) }
    }

    @Test fun entryIsUsefulWithoutAiAndConversationRequiresExplicitTap() {
        var backedOut=false
        compose.setContent {
            RikkahubTheme {
                P14TickTickEntryDetailScreen(
                    entryBody="一条独立保存的旅行观察",createdAt=1_790_000_000_000L,
                    conversationId="conversation-synthetic-1",messages=listOf(
                        P14ProductMessageEntity("synthetic-reply", "conversation-synthetic-1", "assistant",
                            "可以把这条观察留作旅行素材，随时继续补充。", deliveryState="SYNCED", createdAtEpochMs=1_790_000_010_000L),
                    ),
                    input="",onInputChange={},onSend={},sending=false,canChat=true,
                    status=null,onBack={backedOut=true},onAdvanced={},
                )
            }
        }
        compose.onNodeWithTag("p14-entry-body").assertIsDisplayed()
        compose.onNodeWithTag("p14-entry-message-input").assertDoesNotExist()
        screenshot("02-entry-detail-light")
        compose.onNodeWithTag("p14-inbox-open-ai").performClick()
        compose.onNodeWithTag("p14-entry-message-input").assertIsDisplayed()
        compose.onNodeWithText("与 AI 讨论").assertIsDisplayed()
        screenshot("03-ai-conversation-light")
        compose.runOnIdle { assertTrue(!backedOut) }
        pressBack()
        compose.onNodeWithTag("p14-entry-message-input").assertDoesNotExist()
        compose.onNodeWithText("条目详情").assertIsDisplayed()
        compose.onNodeWithTag("p14-entry-back").performClick()
        compose.runOnIdle { assertTrue(backedOut) }
    }

    @Test fun archivedChipUsesRealArchiveStateNotFakeTaskCompletion() {
        var archivedChosen=false
        compose.setContent {
            RikkahubTheme {
                P14TickTickInboxScreen(
                    rows=listOf(entry),archived=false,
                    onShowArchived={archivedChosen=it},
                    draftText="",onDraftChange={},saving=false,status=null,
                    onQuickSave={},onRichCapture={},onOpen={},onToggleArchive={},
                    onMore={},onLoadMore={},hasMore=false,onBack={},
                )
            }
        }
        compose.onNodeWithTag("p14-inbox-archived-tab").performClick()
        compose.runOnIdle { assertTrue(archivedChosen) }
    }
    @Test fun inboxFollowsAndroidDarkConfiguration() {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                RikkahubTheme {
                    P14TickTickInboxScreen(
                        rows=listOf(entry), archived=false, onShowArchived={},
                        draftText="", onDraftChange={}, saving=false, status=null,
                        onQuickSave={}, onRichCapture={}, onOpen={}, onToggleArchive={},
                        onMore={}, onLoadMore={}, hasMore=false, onBack={},
                    )
                }
            }
        }
        compose.onNodeWithText("北海道旅行计划").assertIsDisplayed()
        screenshot("04-inbox-dark")
    }

    @Test fun classificationReusesExistingViewIdentity() {
        var chosen: String? = null
        compose.setContent {
            RikkahubTheme {
                P14TickTickEntryDetailScreen(
                    entryBody="分类测试记录", createdAt=1_790_000_000_000L,
                    conversationId="conversation-synthetic-1", messages=emptyList(),
                    input="", onInputChange={}, onSend={}, sending=false, canChat=true,
                    status=null, onBack={}, onAdvanced={},
                    availableViews=listOf("todo", "idea"), selectedViews=setOf("idea"),
                    onToggleView={chosen=it},
                )
            }
        }
        compose.onNodeWithTag("p14-entry-categories").performClick()
        compose.onNodeWithText("自定义标签、日历和提醒：待实现").assertIsDisplayed()
        screenshot("05-classification-existing-views")
        compose.onNodeWithTag("p14-entry-view-todo").performClick()
        compose.runOnIdle { assertEquals("todo", chosen) }
        pressBack()
        compose.onNodeWithTag("p14-entry-body").assertIsDisplayed()
    }

}
