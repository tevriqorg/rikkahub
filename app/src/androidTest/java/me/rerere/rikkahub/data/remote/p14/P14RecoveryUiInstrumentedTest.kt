package me.rerere.rikkahub.data.remote.p14

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
