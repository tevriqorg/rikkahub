package me.rerere.rikkahub.data.remote.p14

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Synthetic-only emulator acceptance. Invoke individual methods; offline phases precede pairing.
 * seedOffline -> external adb am force-stop -> verifyOffline verifies an actual process boundary.
 * A seed -> B continue -> A verify uses two independent emulator /data and Android Keystores.
 * This is neither physical-device certification nor a Hermes runtime acceptance substitute.
 */
@RunWith(AndroidJUnit4::class)
class P14RecoveryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private fun product() = P14ProductRepository(context, http)
    private fun settings(): SettingsStore = GlobalContext.get().get()
    private fun remote() = P14RemoteRepository(context, settings(), http)
    private fun evidence(message: String) { android.util.Log.i("P14_TASK4", message) }
    private val localMessageId = Uuid.parse("ae800001-0000-4000-8000-000000000001")
    private val multiMessageId = Uuid.parse("ae800002-0000-4000-8000-000000000001")
    private fun offlineMessage() = UIMessage(localMessageId, MessageRole.USER, listOf(
        UIMessagePart.Text("task4-offline-accepted"),
        UIMessagePart.Audio(Uri.fromFile(File(context.filesDir, "task4-voice.m4a")).toString(), buildJsonObject {
            put("p14_media_role", "voice_message"); put("p14_mime_type", "audio/mp4"); put("p14_duration_ms", 1200)
        }),
    ))

    @Test fun localContracts() = runBlocking {
        // Real on-device Room open/reopen and migration validation, not a Python SQLite stand-in.
        remoteMigrationPreservesPending()
        productMigrationPreservesObservation()
        val draftStore = P14CaptureDraftStore(context)
        val source = File(context.cacheDir, "task4-source.txt").apply { writeText("synthetic-owned-copy") }
        val material = draftStore.import(Uri.fromFile(source))
        val draft = P14CaptureDraft(text = "task4-persisted-draft", materials = listOf(material))
        draftStore.save(draft)
        source.delete()
        assertEquals(draft, P14CaptureDraftStore(context).read())
        assertEquals("synthetic-owned-copy", File(requireNotNull(Uri.parse(material.locator).path)).readText())
        val profile = P14ProfileStore(context)
        val original = profile.exportProfile()
        assertFalse(profile.locationEnabled())
        assertTrue(profile.captureEnabled("text"))
        val invalid = original.replace("id: personal-default", "id: rejected-profile") + "\napi_key: NEVER-A-REAL-SECRET\n"
        assertTrue(runCatching { profile.importProfile(invalid) }.isFailure)
        assertEquals(original, P14ProfileStore(context).exportProfile())
        assertFalse(P14CapabilityRegistry.granted(context).contains("LOCATION"))
        assertFalse(P14CapabilityRegistry.granted(context).contains("MICROPHONE"))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(P14CapabilityRegistry.resolve(original, emptySet()).any { it.id == "voice" && it.state == P14Availability.PERMISSION_REQUIRED })
        manifestCacheContracts()
        notificationProjection()
        evidence("localContracts PASS: Room legacy migration, owned draft copy, Profile rollback/permission-denied, Manifest LKG/fallback/upgrade-required, OS notification projection")
    }

    private suspend fun remoteMigrationPreservesPending() {
        val name = "task4-remote-v2.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE p14_notifications (notification_id TEXT NOT NULL PRIMARY KEY,conversation_id TEXT,title TEXT NOT NULL,body TEXT NOT NULL,severity TEXT NOT NULL,interaction_id TEXT,state TEXT NOT NULL,revision INTEGER NOT NULL,expires_at TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_p14_notifications_state ON p14_notifications(state)")
            db.execSQL("CREATE INDEX index_p14_notifications_conversation_id ON p14_notifications(conversation_id)")
            db.execSQL("CREATE TABLE p14_interactions (interaction_id TEXT NOT NULL PRIMARY KEY,conversation_id TEXT NOT NULL,notification_id TEXT,prompt TEXT NOT NULL,options_json TEXT NOT NULL,status TEXT NOT NULL,response TEXT,actionable INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_p14_interactions_status ON p14_interactions(status)")
            db.execSQL("CREATE INDEX index_p14_interactions_conversation_id ON p14_interactions(conversation_id)")
            db.execSQL("CREATE TABLE p14_operations (operation_id TEXT NOT NULL PRIMARY KEY,notification_id TEXT NOT NULL,type TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_p14_operations_state ON p14_operations(state)")
            db.execSQL("INSERT INTO p14_interactions VALUES ('i','c','n','prompt','[]','RESPONDED','yes',1)")
            db.execSQL("INSERT INTO p14_notifications VALUES ('n','c','title','body','INFO','i','UNREAD',1,NULL,'2026-10-20T00:00:00Z','2026-10-20T00:00:00Z')")
            db.execSQL("INSERT INTO p14_operations VALUES ('stable-op','n','INTERACTION','{}','IN_FLIGHT',1)")
            db.version = 2
        }
        repeat(2) {
            val db = Room.databaseBuilder(context, P14RemoteDatabase::class.java, name)
                .addMigrations(P14RemoteDatabase.MIGRATION_2_3).build()
            try {
                assertEquals("yes", db.dao().interaction("i")?.response)
                assertEquals("UNREAD", db.dao().notification("n")?.state)
                val operation = db.dao().pendingOperations().single()
                assertEquals("stable-op", operation.operationId)
                assertEquals("IN_FLIGHT", operation.state)
                assertNull(operation.conversationId)
            } finally { db.close() }
        }
        context.deleteDatabase(name)
    }

    private suspend fun productMigrationPreservesObservation() {
        val name = "task4-product-v5.db"
        context.deleteDatabase(name)
        // Build all unaffected tables with Room, then reconstruct the documented legacy v5
        // Observation table. This exercises the actual Room schema validator after upgrade.
        Room.databaseBuilder(context, P14ProductDatabase::class.java, name).build().let { db ->
            try { db.dao().upsertEntry(P14EntryEntity("preserved-entry", "keep-original", 1)) }
            finally { db.close() }
        }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DROP TABLE p14_observations")
            db.execSQL("CREATE TABLE p14_observations (observation_id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,accuracy_m REAL,captured_at_epoch_ms INTEGER NOT NULL,state TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_p14_observations_state ON p14_observations(state)")
            db.execSQL("CREATE INDEX index_p14_observations_captured_at_epoch_ms ON p14_observations(captured_at_epoch_ms)")
            db.execSQL("INSERT INTO p14_observations VALUES ('preserved-observation','LOCATION',35.0,139.0,20.0,1,'PENDING')")
            db.execSQL("INSERT INTO p14_observation_outbox VALUES ('preserved-obs-op','preserved-observation','IN_FLIGHT')")
            db.version = 5
        }
        repeat(2) {
            val db = Room.databaseBuilder(context, P14ProductDatabase::class.java, name)
                .addMigrations(P14ProductDatabase.MIGRATION_5_6).build()
            try {
                assertEquals("keep-original", db.dao().entry("preserved-entry")?.bodyText)
                val observation = requireNotNull(db.dao().observation("preserved-observation"))
                assertEquals("legacy-unattributed", observation.profileId)
                assertEquals("unknown", observation.sourceDeviceId)
                assertEquals(35.0, observation.latitude, 0.0)
                assertEquals("preserved-obs-op", db.dao().pendingObservationOutbox().single().operationId)
                assertEquals("IN_FLIGHT", db.dao().pendingObservationOutbox().single().state)
            } finally { db.close() }
        }
        context.deleteDatabase(name)
    }

    private fun manifestCacheContracts() {
        val connection = P14RuntimeConnection("http://127.0.0.1:9/v1", "synthetic-only", "manifest-test")
        val prefs = context.getSharedPreferences("p14-manifest", Context.MODE_PRIVATE)
        prefs.edit().remove(connection.baseUrl).commit()
        assertEquals("BUILTIN_FALLBACK", P14ManifestPolicy.load(context, connection, http).source)
        prefs.edit().putString(connection.baseUrl, """{"schema_version":"p14.remote-manifest/v1","manifest_version":1,"minimum_client_protocol":1,"maximum_client_protocol":1,"compatibility":"COMPATIBLE","enabled_views":["chat","inbox","uncompiled"],"message_capabilities":["text","voice","uncompiled"],"upload_limits":{"max_attachment_bytes":64,"max_attachments_per_message":1}}""").commit()
        val cached = P14ManifestPolicy.load(context, connection, http)
        assertEquals("LAST_KNOWN_GOOD", cached.source)
        assertFalse("uncompiled" in cached.enabledViews)
        assertTrue(runCatching { P14ManifestPolicy.requireAttachment(cached, "VOICE", 65) }.isFailure)
        val upgrade = cached.copy(compatibility = "UPGRADE_REQUIRED")
        assertTrue(runCatching { P14ManifestPolicy.requireChat(upgrade) }.isFailure)
    }

    private suspend fun notificationProjection() {
        val dao = P14RemoteDatabase.get(context).dao()
        val notification = P14NotificationDto("task4-os-notification", "synthetic", "task4-event", "task4-context", "Synthetic title", "Synthetic body", "INFO", interactionId = "task4-os-interaction", state = "UNREAD", revision = 1, createdAt = "2026-10-20T00:00:00Z", updatedAt = "2026-10-20T00:00:00Z")
        dao.upsertInteraction(P14InteractionEntity("task4-os-interaction", "task4-context", notification.notificationId, "Choose", """[{"id":"yes","label":"Yes"}]""", "PENDING", null, true))
        P14SystemNotifications.project(context, dao, listOf(notification))
        val nm = context.getSystemService(NotificationManager::class.java)
        val deadline = android.os.SystemClock.elapsedRealtime() + 3000
        while (nm.activeNotifications.none { it.id == notification.notificationId.hashCode() } && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(50)
        }
        val posted = nm.activeNotifications.single { it.id == notification.notificationId.hashCode() }.notification
        assertNotNull(posted.contentIntent)
        assertTrue(posted.contentIntent.isImmutable)
        assertEquals(1, posted.actions.size)
        assertEquals("Yes", posted.actions.single().title.toString())
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertNotNull(posted.publicVersion)
        P14SystemNotifications.project(context, dao, listOf(notification.copy(state = "RESOLVED")))
        assertFalse(nm.activeNotifications.any { it.id == notification.notificationId.hashCode() })
    }

    @Test fun seedOffline() = runBlocking {
        assertNull("Offline phase must precede device pairing", P14RuntimeCredentialStore(context).activeConnection())
        File(context.filesDir, "task4-voice.m4a").writeBytes(byteArrayOf(0, 1, 2, 3, 4))
        val accepted = product().acceptRikkaMessage("task4-offline", "ae800001-0000-4000-8000-000000000002", offlineMessage(), "ae800001-0000-4000-8000-000000000003", "ae800001-0000-4000-8000-000000000004")
        context.getSharedPreferences("task4-evidence", Context.MODE_PRIVATE).edit().putString("offline-conversation", accepted.conversationId).commit()
        val dao = P14ProductDatabase.get(context).dao()
        dao.markOutboxInFlight(accepted.clientOperationId)
        assertEquals("LOCAL_PENDING", dao.message(localMessageId.toString())?.deliveryState)
        assertEquals("IN_FLIGHT", dao.pendingOutbox().single { it.messageId == localMessageId.toString() }.state)
        evidence("seedOffline PASS: Entry/Conversation/Message/VOICE/Outbox transaction persisted before any credential/network; process now requires external force-stop")
    }

    @Test fun verifyOffline() = runBlocking {
        val id = requireNotNull(context.getSharedPreferences("task4-evidence", Context.MODE_PRIVATE).getString("offline-conversation", null))
        val repository = product()
        val dao = P14ProductDatabase.get(context).dao()
        assertNotNull(dao.conversation(id))
        assertEquals("task4-offline-accepted", dao.message(localMessageId.toString())?.bodyText)
        val voice = dao.attachmentsForMessage(localMessageId.toString()).single()
        assertEquals("VOICE", voice.kind)
        assertEquals("audio/mp4", voice.mimeType)
        assertEquals(1200L, voice.durationMs)
        assertEquals(5L, File(requireNotNull(Uri.parse(voice.localLocator).path)).length())
        assertTrue(recoverP14AttachmentPart(voice) is UIMessagePart.Audio)
        assertEquals("IN_FLIGHT", dao.pendingOutbox().single { it.messageId == localMessageId.toString() }.state)
        repository.acceptRikkaMessage("task4-offline", "ae800001-0000-4000-8000-000000000002", offlineMessage(), "ae800001-0000-4000-8000-000000000003", "ae800001-0000-4000-8000-000000000004")
        assertEquals(1, dao.listMessages(id).size)
        assertEquals(1, dao.pendingOutbox().count { it.messageId == localMessageId.toString() })
        assertEquals("task4-persisted-draft", P14CaptureDraftStore(context).read().text)
        evidence("verifyOffline PASS after external force-stop: no duplicate accepted mutation, IN_FLIGHT retry eligible, VOICE source and draft retained")
    }

    private suspend fun pair(): P14RuntimeConnection {
        val base = requireNotNull(args.getString("p14BaseUrl"))
        val token = requireNotNull(args.getString("p14OwnerToken"))
        require(token == "task4-owner-synthetic-only") { "Synthetic fixture only" }
        require(base.startsWith("http://10.0.2.2:")) { "Emulator fixture only" }
        val provider = ProviderSetting.OpenAI(id = Uuid.parse("ae800003-0000-4000-8000-000000000001"), name = "P14 Task4 synthetic", baseUrl = base, apiKey = token)
        settings().update { it.copy(providers = it.providers.filterNot { p -> p.id == provider.id } + provider) }
        val access = P14RuntimeAccess(context, settings(), http)
        val connection = access.connection(provider)
        assertNotEquals(token, connection.bearerToken)
        http.newCall(Request.Builder().url("$base/models").header("Authorization", "Bearer ${connection.bearerToken}").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body!!.string().contains("p14-hermes"))
        }
        val stored = context.getSharedPreferences("p14-rikkahub-runtime", Context.MODE_PRIVATE).all
        assertFalse(stored.values.any { it.toString() == connection.bearerToken })
        assertEquals(connection.bearerToken, P14RuntimeCredentialStore(context).load(connection.providerId, connection.baseUrl))
        return connection
    }

    @Test fun deviceASeed() = runBlocking {
        pair()
        val repository = product()
        val accepted = repository.acceptRikkaMessage("task4-multidevice", "ae800002-0000-4000-8000-000000000002", UIMessage(multiMessageId, MessageRole.USER, listOf(UIMessagePart.Text("task4-device-A"))), "ae800001-0000-4000-8000-000000000003", "ae800001-0000-4000-8000-000000000004")
        assertTrue(repository.syncAndFlushActive())
        assertFalse(P14ProductDatabase.get(context).dao().pendingOutbox().any { it.messageId == multiMessageId.toString() })
        evidence("deviceASeed PASS: normal runtime setup/device credential + model discovery + locally accepted Entry flushed; conversation=${accepted.conversationId}")
    }

    @Test fun deviceBContinue() = runBlocking {
        pair()
        val repository = product()
        assertTrue(repository.replayActive())
        val entry = repository.directoryPage(0, 100).single { it.bodyText == "task4-device-A" }
        repository.hydrateEntryState(entry.entryId, entry.conversationId)
        repository.loadEntryHistory(entry.entryId)
        assertTrue(repository.messages(entry.conversationId).first().any { it.bodyText == "task4-device-A" })
        repository.acceptEntryContinuation(entry.conversationId, "task4-device-B")
        assertTrue(repository.syncAndFlushActive())
        remote().refreshActive()
        args.getString("p14NotificationId")?.let { assertNotNull(remote().notification(it)) }
        assertTrue(repository.currentCursor() > 0)
        evidence("deviceBContinue PASS: independent device bootstrap/lazy history, same Entry conversation continuation + durable Inbox; conversation=${entry.conversationId}")
    }

    @Test fun deviceBMutations() = runBlocking {
        val repository = product()
        val runtime = requireNotNull(P14RuntimeCredentialStore(context).activeConnection())
        val provider = ProviderSetting.OpenAI(baseUrl = runtime.baseUrl, apiKey = runtime.bearerToken)
        val control = P14RemoteControlClient(http)
        val entry = repository.directoryPage(0, 100).single { it.bodyText == "task4-device-A" }
        val state = control.getConversationState(provider, entry.conversationId)
        val changed = control.setConversationState(provider, entry.conversationId, state.revision, pinned = true, archived = true)
        assertTrue(changed.pinned && changed.archived)
        repository.replayActive()
        assertTrue(requireNotNull(repository.conversation(entry.conversationId)).pinned)
        assertTrue(requireNotNull(repository.conversation(entry.conversationId)).archived)
        val accepted = repository.acceptEntryContinuation(entry.conversationId, "task4-editable")
        repository.syncAndFlushActive()
        val mid = Uuid.parse(accepted.messageId)
        val message = control.getMessageState(provider, entry.conversationId, mid)
        val revision = control.editMessage(provider, entry.conversationId, mid, message.revision, "task4-edited")
        assertEquals("task4-edited", control.getMessageState(provider, entry.conversationId, mid).bodyText)
        control.deleteMessage(provider, entry.conversationId, mid, revision)
        assertTrue(control.getMessageState(provider, entry.conversationId, mid).deleted)
        repository.replayActive()
        assertTrue(requireNotNull(repository.message(mid.toString())).tombstone)
        for (view in listOf("todo", "idea", "research", "execute")) repository.assignEntryView(entry.entryId, view, true)
        repository.syncAndFlushActive()
        for (view in listOf("todo", "idea", "research", "execute")) {
            assertTrue(repository.directoryPage(0, 100, view).any { it.entryId == entry.entryId && it.conversationId == entry.conversationId })
        }
        evidence("deviceBMutations PASS: revisioned Pin/Archive, edit/tombstone, four Views share same Entry/Conversation")
    }

    @Test fun observationPolicy() = runBlocking {
        val profile = P14ProfileStore(context)
        val before = profile.exportProfile()
        val enabled = before.replace("id: personal-default", "id: task4-location-profile")
            .replace("location:\n    enabled: false", "location:\n    enabled: true")
        assertNotEquals(before, enabled)
        profile.importProfile(enabled)
        try {
            assertTrue(profile.locationEnabled())
            val entryCount = P14ProductDatabase.get(context).dao().observeEntries().first().size
            val id = product().acceptLocationObservation(35.0, 139.0, 10.0, "foreground")
            val row = requireNotNull(P14ProductDatabase.get(context).dao().observation(id))
            assertEquals("task4-location-profile", row.profileId)
            assertEquals("foreground", row.permissionState)
            assertEquals(P14RuntimeCredentialStore(context).deviceId(), row.sourceDeviceId)
            assertEquals(entryCount, P14ProductDatabase.get(context).dao().observeEntries().first().size)
            product().syncAndFlushActive()
            assertFalse(P14ProductDatabase.get(context).dao().pendingObservationOutbox().any { it.observationId == id })
            evidence("observationPolicy PASS: explicit opt-in synthetic coordinates, provenance/Outbox and server commit, separate Entry count unchanged; no GPS sensor/background efficacy claim")
        } finally { profile.importProfile(before) }
    }

    @Test fun deviceAVerify() = runBlocking {
        val repository = product()
        assertTrue(repository.replayActive())
        val entry = repository.directoryPage(0, 100).single { it.bodyText == "task4-device-A" }
        repository.loadEntryHistory(entry.entryId)
        val before = repository.messages(entry.conversationId).first()
        assertEquals(1, before.count { it.bodyText == "task4-device-A" })
        assertEquals(1, before.count { it.bodyText == "task4-device-B" })
        val cursor = repository.currentCursor()
        repository.replayActive()
        assertEquals(before, repository.messages(entry.conversationId).first())
        assertTrue(repository.currentCursor() >= cursor)
        val state = requireNotNull(repository.conversation(entry.conversationId))
        assertTrue(state.pinned && state.archived)
        assertTrue(repository.messages(entry.conversationId).first().any { it.tombstone })
        for (view in listOf("todo", "idea", "research", "execute")) {
            assertTrue(repository.directoryPage(0, 100, view).any { it.entryId == entry.entryId })
        }
        evidence("deviceAVerify PASS: A→B→A converged same conversation, deduped replay, durable cursor=$cursor")
    }
}
