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

/** Real launcher/settings/task flow; no extra host Activity competing for window focus. */
@RunWith(AndroidJUnit4::class)
class P14InboxLauncherInstrumentedTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    @Test fun launcherOpensInboxAndBackExits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = android.content.Intent(context, me.rerere.rikkahub.RouteActivity::class.java)
            .setAction(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val scenario = androidx.test.core.app.ActivityScenario.launch<me.rerere.rikkahub.RouteActivity>(intent)
        try {
            compose.waitUntil(timeoutMillis = 20_000L) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("p14-inbox-add"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("p14-inbox-list").assertIsDisplayed()
            compose.onNodeWithTag("p14-inbox-add").assertIsDisplayed()
            compose.onNodeWithText("☰").performClick()
            compose.onNodeWithText("连接与设置").performClick()
            compose.onNodeWithText(context.getString(me.rerere.rikkahub.R.string.setting_page_providers))
                .assertIsDisplayed()
            // A warm launcher intent must return from settings to records as well.
            context.startActivity(android.content.Intent(context, me.rerere.rikkahub.RouteActivity::class.java)
                .setAction(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            compose.waitUntil(timeoutMillis = 20_000L) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("p14-inbox-add"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("p14-inbox-add").assertIsDisplayed()
            androidx.test.espresso.Espresso.pressBackUnconditionally()
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
            while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED &&
                android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50L)
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                    .filterIsInstance<P14EntryActivity>().distinct().forEach { it.finish() }
            }
            scenario.close()
        }
    }

}
