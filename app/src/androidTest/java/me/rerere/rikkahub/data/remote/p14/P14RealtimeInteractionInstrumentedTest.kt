package me.rerere.rikkahub.data.remote.p14

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real paired emulator / actual synthetic Runner Server acceptance, before revocation.
 * No token is written to markers. Runtime delivery includes its genuine reconnect/replay path;
 * successful arrival does not by itself prove a WebSocket frame was used.
 */
@RunWith(AndroidJUnit4::class)
class P14RealtimeInteractionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val http = OkHttpClient()
    private fun product() = P14ProductRepository(context, http)
    private fun remote() = P14RemoteRepository(context, GlobalContext.get().get<SettingsStore>(), http)
    private fun guard(): String {
        val base = requireNotNull(args.getString("p14BaseUrl"))
        require(base == "http://10.0.2.2:8014/v1") { "Synthetic Runner emulator endpoint required" }
        require(requireNotNull(P14RuntimeCredentialStore(context).activeConnection()).baseUrl.trimEnd('/') == base)
        return base
    }
    private fun runId(): String = requireNotNull(args.getString("p14RealtimeRunId")).also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,48}")))
    }
    private fun id(kind: String) = UUID.nameUUIDFromBytes("task4-${runId()}-$kind".toByteArray()).toString()
    private fun marker(name: String, value: String) {
        File(context.filesDir, name).outputStream().use { it.write(value.toByteArray()); it.fd.sync() }
    }
    private fun evidence(value: String) { android.util.Log.i("P14_TASK4", value) }
    private fun api(path: String, payload: JsonObject? = null, producer: Boolean = false): JsonObject {
        val token = if (producer) "task4-producer-synthetic-only" else "task4-owner-synthetic-only"
        val request = Request.Builder().url("${guard()}/$path").header("Authorization", "Bearer $token")
        if (payload == null) request.get() else request.post(payload.toString().toRequestBody("application/json".toMediaType()))
        return http.newCall(request.build()).execute().use { response ->
            // Do not include wire bodies/headers in failures: credentials stay in memory.
            assertTrue("Synthetic API $path HTTP ${response.code}", response.isSuccessful)
            Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
        }
    }
    private fun fixture(kind: String, expires: String? = null): String {
        val conversation = id("$kind-conversation")
        api("entries", buildJsonObject {
            put("client_operation_id", id("$kind-create")); put("entry_id", id("$kind-entry"))
            put("conversation_id", conversation); put("message_id", id("$kind-message"))
            put("text", "Synthetic task4 ${runId()} $kind")
        })
        // This is the owner-authenticated protocol route; no Hermes process is started.
        api("hermes/conversations/$conversation/interactions", buildJsonObject {
            put("interaction_id", id("$kind-interaction")); put("kind", "approval")
            put("prompt_text", "Synthetic task4 $kind")
            put("options", buildJsonArray {
                add(buildJsonObject { put("id", "allow"); put("label", "Task4 $kind allow ${runId()}") })
                add(buildJsonObject { put("id", "deny"); put("label", "Task4 $kind deny ${runId()}") })
            })
            put("backend_ref", buildJsonObject { put("fixture", "task4-synthetic-only") })
        })
        return requireNotNull(api("notification-producers/task4/notifications", buildJsonObject {
            put("producer_event_id", id("$kind-event")); put("conversation_id", conversation)
            put("interaction_id", id("$kind-interaction")); put("title", "Task4 ${runId()} $kind")
            put("body", "Synthetic once-only choice; no tool execution")
            expires?.let { put("expires_at", it) }
        }, producer = true)["notification_id"]).jsonPrimitive.content
    }
    private fun notification(kind: String): String = requireNotNull(context.getSharedPreferences("task4-realtime", 0).getString(kind, null))
    private fun remember(kind: String, value: String) {
        assertTrue(context.getSharedPreferences("task4-realtime", 0).edit().putString(kind, value).commit())
    }
    private fun operations(notification: String): List<Pair<String, String>> =
        P14RemoteDatabase.get(context).openHelper.readableDatabase.query(
            "SELECT operation_id,state FROM p14_operations WHERE notification_id=? AND type='INTERACTION'", arrayOf(notification)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
    private suspend fun settled(notification: String): Pair<String, String> = withTimeout(30000) {
        var rows = operations(notification)
        while (rows.size != 1 || rows.single().second !in setOf("ACKED", "CONFLICT")) {
            delay(100); remote().processPendingActive(); rows = operations(notification)
        }
        rows.single()
    }

    /** Start on A in background; wait for ready marker externally, then invoke liveContinue on B. */
    @Test fun liveObserve(): Unit = runBlocking {
        guard()
        val repository = product()
        val conversation = id("live-conversation")
        api("entries", buildJsonObject {
            put("client_operation_id", id("live-create")); put("entry_id", id("live-entry"))
            put("conversation_id", conversation); put("message_id", id("live-message"))
            put("text", "Synthetic task4 live ${runId()}")
        })
        assertTrue(repository.replayActive())
        repository.hydrateEntryState(id("live-entry"), conversation)
        assertFalse(repository.messages(conversation).first().any { it.bodyText == "task4-live-${runId()}" })
        val before = repository.currentCursor()
        P14ProductSyncRuntime.start(context, http)
        marker("task4-live-ready", runId())
        // From this point only real Runtime may fetch/apply events; test only observes Room.
        withTimeout(60000) {
            while (repository.messages(conversation).first().none { it.bodyText == "task4-live-${runId()}" }) delay(100)
        }
        assertEquals(1, repository.messages(conversation).first().count { it.bodyText == "task4-live-${runId()}" })
        assertTrue(repository.currentCursor() > before)
        marker("task4-live-received", runId())
        evidence("liveObserve PASS: real Runtime received B continuation without test replay; reconnect/replay fallback permitted")
    }

    @Test fun liveContinue(): Unit = runBlocking {
        guard()
        val repository = product()
        assertTrue(repository.replayActive())
        repository.hydrateEntryState(id("live-entry"), id("live-conversation"))
        repository.acceptEntryContinuation(id("live-conversation"), "task4-live-${runId()}")
        assertTrue(repository.syncAndFlushActive())
        evidence("liveContinue PASS: B locally accepted and flushed independent live continuation")
    }

    /** Run sequentially on A then B, before either raceRespond; fixtures are protocol-idempotent. */
    @Test fun racePrepare(): Unit = runBlocking {
        val n = fixture("race")
        remember("race", n)
        assertTrue(remote().refreshActive())
        assertEquals("PENDING", requireNotNull(remote().interaction(n)).status)
        assertTrue(requireNotNull(remote().interaction(n)).actionable)
        assertTrue(operations(n).isEmpty())
        marker("task4-race-ready", runId())
    }

    /** Start concurrently A/B with same future p14RaceAtEpochMs and distinct allow/deny choices. */
    @Test fun raceRespond(): Unit = runBlocking {
        guard()
        val n = notification("race")
        val choice = requireNotNull(args.getString("p14RaceChoice"))
        require(choice in setOf("allow", "deny"))
        val at = requireNotNull(args.getString("p14RaceAtEpochMs")).toLong()
        require(at - System.currentTimeMillis() in 0..60000) { "Future common race barrier required" }
        val credentials = P14RuntimeCredentialStore(context)
        val connection = requireNotNull(credentials.activeConnection())
        val runtimePrefs = context.getSharedPreferences("p14-rikkahub-runtime", 0)
        // Pause only delivery selection, not the network and not durable operation state.
        // The per-device bearer stays encrypted in its existing Keystore-backed store.
        assertTrue(runtimePrefs.edit().remove("active_provider").remove("active_base").commit())
        try {
            // Finish cancelling older opportunistic work before adding this operation.
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("p14-remote-sync")
                .result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertNull(credentials.activeConnection())
            remote().queueInteraction(n, choice)
            assertEquals(1, operations(n).size)
            assertEquals("PENDING", operations(n).single().second)
            marker("task4-race-waiting", runId())
            // Runner must observe BOTH waiting markers before this common future time.
            while (System.currentTimeMillis() < at) delay(10)
        } finally {
            credentials.markActive(connection.providerId, connection.baseUrl)
        }
        guard()
        assertTrue(remote().processPendingActive())
        val result = settled(n)
        remember("race-choice", choice)
        marker("task4-race-outcome", result.second)
        assertTrue(result.second in setOf("ACKED", "CONFLICT"))
    }

    /** Run on both after both responses finish; root also checks one Server event/Idempotency row. */
    @Test fun raceVerify(): Unit = runBlocking {
        guard()
        val n = notification("race")
        val current = api("interactions/${id("race-interaction")}")
        assertEquals("RESPONDED", current["status"]?.jsonPrimitive?.content)
        val winner = requireNotNull(current["response"]).jsonPrimitive.content
        val choice = notification("race-choice")
        val expected = if (winner == choice) "ACKED" else "CONFLICT"
        assertEquals(expected, operations(n).single().second)
        remote().refreshActive()
        assertEquals(winner, requireNotNull(remote().interaction(n)).response)
        assertFalse(requireNotNull(remote().interaction(n)).actionable)
        assertTrue(runCatching { remote().queueInteraction(n, choice) }.isFailure)
        assertEquals(1, operations(n).size)
        evidence("raceVerify PASS: own operation $expected; authoritative response converged and repeat locally rejected")
    }

    private fun accessibilitySubtree(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val result = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < 600) {
            val node = pending.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.addLast(it) }
        }
        return result
    }

    /** A only: stale client expiry rejection, then actual SystemUI option click on independent fixture. */
    @Test fun expiryAndDirectNotificationAction(): Unit = runBlocking {
        val expires = Instant.now().plusSeconds(12)
        val expired = fixture("expiry", expires.toString())
        remote().refreshActive()
        assertTrue(requireNotNull(remote().interaction(expired)).actionable)
        while (Instant.now().isBefore(expires.plusSeconds(1))) delay(100)
        remote().queueInteraction(expired, "allow")
        remote().processPendingActive()
        assertEquals("CONFLICT", settled(expired).second)
        assertEquals("PENDING", api("interactions/${id("expiry-interaction")}")["status"]?.jsonPrimitive?.content)
        assertFalse(requireNotNull(remote().interaction(expired)).actionable)
        assertTrue(runCatching { remote().queueInteraction(expired, "allow") }.isFailure)
        assertEquals(1, operations(expired).size)

        val direct = fixture("direct")
        remote().refreshActive()
        assertTrue(operations(direct).isEmpty())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = context.getSystemService(NotificationManager::class.java)
        withTimeout(10000) { while (manager.activeNotifications.none { it.id == direct.hashCode() }) delay(100) }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        val label = "Task4 direct allow ${runId()}"
        var clicked = false
        var expanded = false
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (!clicked && SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val nodes = accessibilitySubtree(root)
            // Some SystemUI versions uppercase action text; identity remains exact apart from case.
            val option = nodes.firstOrNull { it.text?.toString()?.equals(label, ignoreCase = true) == true }
            if (option != null) {
                var target: AccessibilityNodeInfo? = option
                repeat(5) {
                    if (!clicked) clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    target = target?.parent
                }
            } else if (!expanded) {
                val title = "Task4 ${runId()} direct"
                var ancestor = nodes.firstOrNull { it.text?.toString() == title }?.parent
                var level = 0
                while (!expanded && ancestor != null && level++ < 6) {
                    val subtree = accessibilitySubtree(ancestor)
                    // Never climb into a container covering other notifications or the whole shade.
                    if (subtree.any { node ->
                        node.viewIdResourceName?.endsWith("/title") == true &&
                            !node.text.isNullOrBlank() && node.text.toString() != title
                    }) break
                    expanded = if (ancestor.actionList.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }) {
                        ancestor.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                    } else {
                        subtree.firstOrNull { it.viewIdResourceName?.endsWith("/expand_button") == true }
                            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                    ancestor = ancestor.parent
                }
            }
            if (!clicked) delay(100)
        }
        assertTrue("Actual SystemUI option must be clicked", clicked)
        assertEquals("ACKED", settled(direct).second)
        val authoritative = api("interactions/${id("direct-interaction")}")
        assertEquals("RESPONDED", authoritative["status"]?.jsonPrimitive?.content)
        assertEquals("allow", authoritative["response"]?.jsonPrimitive?.content)
        remote().refreshActive()
        assertFalse(requireNotNull(remote().interaction(direct)).actionable)
        assertEquals(1, operations(direct).size)
        evidence("expiryAndDirectNotificationAction PASS: expired stale response CONFLICT; actual SystemUI option -> Activity -> durable Outbox -> one authoritative response")
    }
}
