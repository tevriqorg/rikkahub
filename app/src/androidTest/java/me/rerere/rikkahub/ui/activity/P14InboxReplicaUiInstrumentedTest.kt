package me.rerere.rikkahub.ui.activity

import androidx.compose.ui.test.assertExists
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
        compose.onNodeWithTag("p14-inbox-list").assertExists()
        compose.onNodeWithText("收件箱").assertIsDisplayed()
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
}
