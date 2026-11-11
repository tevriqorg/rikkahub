package me.rerere.rikkahub.ui.activity

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.rememberToasterState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import java.io.File
import me.rerere.rikkahub.data.remote.p14.P14ProductMessageEntity
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.p14ReturnToNativeChat
import androidx.navigation3.runtime.NavKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.remote.p14.P14InboxRow
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Match the real Activity's native UIAvatar composition requirements in tests. */
@Composable
private fun P14TestTheme(content: @Composable () -> Unit) {
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(LocalToaster provides toasterState) {
            content()
        }
    }
}

/** Synthetic, offline Inbox reproduction. Does not access user data, P14 Server or Hermes. */
@RunWith(AndroidJUnit4::class)
class P14InboxReplicaUiInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
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
            P14TestTheme {
                P14TickTickInboxScreen(
                    rows=listOf(entry),archived=false,onShowArchived={},
                    draftText="",onDraftChange={},saving=false,status=null,onQuickSave={},
                    onRichCapture={},onOpen={chosen=it},onToggleArchive={},
                    onMore={},onLoadMore={},hasMore=false,onBack={},
                )
            }
        }
        compose.onNodeWithTag("p14-inbox-list").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertDoesNotExist()
        compose.onNodeWithText("北海道旅行计划").assertIsDisplayed()
        screenshot("01-inbox-light")
        compose.onNodeWithText("北海道旅行计划").performClick()
        compose.runOnIdle { assertEquals("conversation-synthetic-1",chosen) }
    }

    @Test fun inboxUsesNativeRikkaHubDrawerInsteadOfCustomWorkspaceLinks() {
        compose.setContent {
            P14TestTheme {
                P14TickTickInboxScreen(
                    rows=listOf(entry),archived=false,onShowArchived={},
                    draftText="",onDraftChange={},saving=false,status=null,onQuickSave={},
                    onRichCapture={},onOpen={},onToggleArchive={},
                    onMore={},onLoadMore={},hasMore=false,onBack={},
                )
            }
        }
        compose.onNodeWithTag("p14-inbox-list").assertIsDisplayed()
        compose.onNodeWithTag("p14-inbox-open-workbench").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开侧边栏").performClick()
        compose.onNodeWithText("收集箱").assertIsDisplayed()
        compose.onNodeWithText("通知").assertIsDisplayed()
        compose.onNodeWithText("工作空间").assertDoesNotExist()
        compose.onNodeWithText("AI 对话 · 当前工作区").assertDoesNotExist()
    }

    @Test fun aiWorkspaceShortcutRecoversLastNativeChatFromSettings() {
        val chat=Screen.Chat("00000000-0000-4000-8000-000000000001")
        val stack=mutableListOf<NavKey>(chat,Screen.Setting,Screen.SettingProvider)
        p14ReturnToNativeChat(stack)
        assertEquals(listOf<NavKey>(chat),stack)
    }

    @Test fun workspaceIntentsBringExistingActivitiesToFrontWithoutNewChat() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val ai=P14WorkspaceNavigation.aiIntent(context)
        val inbox=P14WorkspaceNavigation.inboxIntent(context)
        val settings=P14WorkspaceNavigation.settingsIntent(context)
        val flags=android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags,ai.flags and flags)
        assertEquals(flags,inbox.flags and flags)
        assertEquals(flags,settings.flags and flags)
        assertEquals(0,settings.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(P14WorkspaceNavigation.ACTION_OPEN_AI,ai.action)
        assertEquals(P14WorkspaceNavigation.ACTION_OPEN_INBOX,inbox.action)
        assertEquals(me.rerere.rikkahub.P14_ACTION_CONNECTION_SETTINGS,settings.action)
        assertEquals("me.rerere.rikkahub.RouteActivity",ai.component?.className)
        assertEquals("me.rerere.rikkahub.ui.activity.P14EntryActivity",inbox.component?.className)
        assertTrue(inbox.getBooleanExtra(P14EntryActivity.EXTRA_FROM_LAUNCHER,false))
        assertTrue(ai.getStringExtra("conversationId")==null)
    }

    @Test fun plusOpensTickTickStyleQuickCapture() {
        var draft=""
        compose.setContent {
            P14TestTheme {
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

    @Test fun quickCaptureSelectionDoesNotRewriteDraftOrLoseCursor() {
        // UX-02: moving the caret / changing the IME composing range is not a text edit.
        // Keep the real TextFieldValue selection, and only persist actual content changes.
        var draft by mutableStateOf("")
        var writes = 0
        compose.setContent {
            P14TestTheme {
                P14TickTickInboxScreen(
                    rows=emptyList(),archived=false,onShowArchived={},
                    draftText=draft,onDraftChange={draft=it;writes++},saving=false,status=null,
                    onQuickSave={},onRichCapture={},onOpen={},onToggleArchive={},
                    onMore={},onLoadMore={},hasMore=false,onBack={},
                )
            }
        }
        compose.onNodeWithTag("p14-inbox-add").performClick()
        compose.onNodeWithTag("p14-inbox-quick-input").performTextReplacement("今天买米")
        compose.runOnIdle { assertEquals("今天买米",draft) }
        var beforeSelection = 0
        compose.runOnIdle { beforeSelection = writes }
        compose.onNodeWithTag("p14-inbox-quick-input")
            .performSemanticsAction(SemanticsActions.SetSelection) { it(2,2,false) }
        compose.runOnIdle { assertEquals("Moving caret must not re-persist draft",beforeSelection,writes) }
        compose.onNodeWithTag("p14-inbox-quick-input").performTextInput("新")
        compose.onNodeWithTag("p14-inbox-quick-input").assertTextEquals("今天新买米")
        compose.runOnIdle { assertEquals("今天新买米",draft) }
        compose.runOnIdle { beforeSelection = writes }
        compose.onNodeWithTag("p14-inbox-quick-input")
            .performSemanticsAction(SemanticsActions.SetSelection) { it(1,3,false) }
        compose.runOnIdle { assertEquals("Selection without content change must not re-persist",beforeSelection,writes) }
    }

    @Test fun entryIsReadableWithoutAiAndNativeChatIsExplicitAction() {
        var backedOut=false
        var openedNativeChat=0
        compose.setContent {
            P14TestTheme {
                P14TickTickEntryDetailScreen(
                    entryBody="一条独立保存的旅行观察",createdAt=1_790_000_000_000L,
                    conversationId="conversation-synthetic-1",messages=emptyList(),
                    input="",onInputChange={},onSend={},sending=false,canChat=true,
                    status=null,onBack={backedOut=true},onAdvanced={},
                    onOpenNativeChat={openedNativeChat++},
                )
            }
        }
        compose.onNodeWithTag("p14-entry-body").assertIsDisplayed()
        compose.onNodeWithTag("p14-entry-message-input").assertDoesNotExist()
        screenshot("02-entry-detail-light")
        compose.onNodeWithTag("p14-inbox-open-ai").performClick()
        compose.runOnIdle { assertEquals(1,openedNativeChat) }
        compose.onNodeWithTag("p14-entry-message-input").assertDoesNotExist()
        compose.onNodeWithTag("p14-entry-body").assertIsDisplayed()
        compose.onNodeWithTag("p14-entry-back").performClick()
        compose.runOnIdle { assertTrue(backedOut) }
    }

    @Test fun onlyOneAiActionOpensNativeRikkaHubWithoutDuplicatedMenuItem() {
        var nativeStarts=0
        compose.setContent {
            P14TestTheme {
                P14TickTickEntryDetailScreen(
                    entryBody="还没讨论过的本地记录",createdAt=1_790_000_000_000L,
                    conversationId="conversation-ai-synthetic-1",messages=emptyList(),
                    input="",onInputChange={},onSend={},sending=false,canChat=true,status=null,
                    onBack={},onAdvanced={},onOpenNativeChat={nativeStarts++},
                )
            }
        }
        compose.onNodeWithTag("p14-inbox-open-ai").performClick()
        compose.runOnIdle { assertEquals(1,nativeStarts) }
        compose.onNodeWithTag("p14-entry-message-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("记录更多操作").performClick()
        compose.onNodeWithText("在 RikkaHub 原生聊天中讨论").assertDoesNotExist()
        compose.onNodeWithText("查看原始 Capture").assertDoesNotExist()
        compose.onNodeWithText("高级管理").assertDoesNotExist()
    }

    @Test fun archivedChipUsesRealArchiveStateNotFakeTaskCompletion() {
        var archivedChosen=false
        compose.setContent {
            P14TestTheme {
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
                P14TestTheme {
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
            P14TestTheme {
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
        compose.onNodeWithText("自定义标签、日历和提醒：待实现").assertDoesNotExist()
        screenshot("05-classification-existing-views")
        compose.onNodeWithTag("p14-entry-view-todo").performClick()
        compose.runOnIdle { assertEquals("todo", chosen) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("p14-entry-body").assertIsDisplayed()
    }

}
