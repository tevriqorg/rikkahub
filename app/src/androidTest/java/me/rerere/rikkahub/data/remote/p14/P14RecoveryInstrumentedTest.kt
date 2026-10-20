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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRuntimeMode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.activity.P14EntryActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
        UIMessagePart.Audio(Uri.fromFile(File(context.filesDir, "task4-voice.wav")).toString(), buildJsonObject {
            put("p14_media_role", "voice_message"); put("p14_mime_type", "audio/wav"); put("p14_duration_ms", 100)
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
        val unavailable=http.newBuilder().addInterceptor { throw java.io.IOException("Synthetic transport unavailable") }.build()
        assertEquals("BUILTIN_FALLBACK", P14ManifestPolicy.load(context, connection, unavailable).source)
        val compatible="""{"schema_version":"p14.remote-manifest/v1","manifest_version":1,"minimum_client_protocol":1,"maximum_client_protocol":1,"compatibility":"COMPATIBLE","enabled_views":["chat","inbox","uncompiled"],"message_capabilities":["text","voice","quick_action","uncompiled"],"quick_action_types":["content.accept"],"upload_limits":{"max_attachment_bytes":64,"max_attachments_per_message":1}}"""
        fun wireClient(body:String)=http.newBuilder().addInterceptor { chain ->
            assertEquals("/v1/remote-manifest",chain.request().url.encodedPath)
            assertEquals("1",chain.request().url.queryParameter("client_protocol"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("Synthetic wire Manifest").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val remote=P14ManifestPolicy.load(context,connection,wireClient(compatible))
        assertEquals("REMOTE",remote.source)
        P14ManifestPolicy.requireChat(remote)
        assertFalse("uncompiled" in remote.enabledViews)
        assertFalse("uncompiled" in remote.messageCapabilities)
        assertEquals(listOf("content.accept"),remote.quickActionTypes)
        assertTrue(runCatching { P14ManifestPolicy.requireAttachment(remote,"VOICE",65) }.isFailure)
        val cached=P14ManifestPolicy.load(context,connection,unavailable)
        assertEquals("LAST_KNOWN_GOOD",cached.source)
        assertEquals(remote.copy(source="LAST_KNOWN_GOOD"),cached)
        val incompatible=compatible.replace("\"COMPATIBLE\"","\"UPGRADE_REQUIRED\"")
            .replace("\"minimum_client_protocol\":1","\"minimum_client_protocol\":999")
            .replace("\"maximum_client_protocol\":1","\"maximum_client_protocol\":999")
        val upgrade=P14ManifestPolicy.load(context,connection,wireClient(incompatible))
        assertEquals("REMOTE",upgrade.source)
        assertEquals("UPGRADE_REQUIRED",upgrade.compatibility)
        assertTrue(upgrade.messageCapabilities.isEmpty())
        assertTrue(upgrade.quickActionTypes.isEmpty())
        assertTrue(runCatching { P14ManifestPolicy.requireChat(upgrade) }.isFailure)
        val limitedCached=P14ManifestPolicy.load(context,connection,unavailable)
        assertEquals("LAST_KNOWN_GOOD",limitedCached.source)
        assertEquals("UPGRADE_REQUIRED",limitedCached.compatibility)
        assertTrue(runCatching { P14ManifestPolicy.requireChat(limitedCached) }.isFailure)
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(P14EntryActivity::class.java.name, null, false)
        try {
            posted.contentIntent.send()
            val opened = requireNotNull(monitor.waitForActivityWithTimeout(8000)) { "Notification did not open Entry Activity" }
            assertEquals(notification.conversationId, opened.intent.getStringExtra(P14EntryActivity.EXTRA_CONVERSATION_ID))
            assertEquals(notification.notificationId, opened.intent.getStringExtra(P14EntryActivity.EXTRA_NOTIFICATION_ID))
            instrumentation.runOnMainSync { opened.finish() }
        } finally { instrumentation.removeMonitor(monitor) }
        P14SystemNotifications.project(context, dao, listOf(notification.copy(state = "RESOLVED")))
        val cancelDeadline = android.os.SystemClock.elapsedRealtime() + 3000
        while (nm.activeNotifications.any { it.id == notification.notificationId.hashCode() } && android.os.SystemClock.elapsedRealtime() < cancelDeadline) {
            Thread.sleep(50)
        }
        assertFalse("Resolved notification must disappear within bounded OS cancellation time", nm.activeNotifications.any { it.id == notification.notificationId.hashCode() })
    }

    @Test fun networkOnline():Unit = verifyNetworkReachability(false)
    @Test fun networkOffline():Unit = verifyNetworkReachability(true)

    private fun verifyNetworkReachability(offline:Boolean) {
        val base=requireNotNull(args.getString("p14BaseUrl"))
        require(base.startsWith("http://10.0.2.2:8014/")) { "Synthetic emulator endpoint only" }
        val health=base.substringBefore("/v1")+"/healthz"
        val probe=OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(3,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3,java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(3,java.util.concurrent.TimeUnit.SECONDS).build()
        val deadline=android.os.SystemClock.elapsedRealtime()+30000
        var attempts=0
        try {
            while(true) {
                attempts++
                try {
                    probe.newCall(Request.Builder().url(health).build()).execute().use { response ->
                        assertFalse("No HTTP response may be received while offline; got ${response.code}",offline)
                        assertEquals(200,response.code)
                        assertTrue(requireNotNull(response.body).string().contains("p14-server"))
                    }
                    evidence("networkOnline PASS: app UID reached actual synthetic Server health; attempts=$attempts")
                    return
                } catch(failure:java.io.IOException) {
                    if(offline) {
                        evidence("networkOffline PASS: app UID cannot reach same synthetic Server endpoint; ${failure.javaClass.simpleName}")
                        return
                    }
                    if(android.os.SystemClock.elapsedRealtime()>=deadline) throw failure
                    android.os.SystemClock.sleep(500)
                }
            }
        } finally { probe.dispatcher.executorService.shutdown();probe.connectionPool.evictAll() }
    }

    @Test fun seedOffline() = runBlocking {
        assertNull("Offline phase must precede device pairing", P14RuntimeCredentialStore(context).activeConnection())
        val pcm = java.nio.ByteBuffer.allocate(1644).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(1636).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1.toShort()).putShort(1.toShort()).putInt(8000).putInt(16000)
            .putShort(2.toShort()).putShort(16.toShort()).put("data".toByteArray()).putInt(1600)
        File(context.filesDir, "task4-voice.wav").writeBytes(pcm.array())
        val model = Model(id = Uuid.parse("ae800001-0000-4000-8000-000000000004"), modelId = "p14-hermes", displayName = "P14 synthetic offline")
        val assistant = Assistant(id = Uuid.parse("ae800001-0000-4000-8000-000000000003"), chatModelId = model.id, name = "P14 synthetic offline", runtimeMode = AssistantRuntimeMode.REMOTE)
        val provider = ProviderSetting.OpenAI(id = Uuid.parse("ae800001-0000-4000-8000-000000000005"), name = "P14 synthetic offline", models = listOf(model), apiKey = "", baseUrl = "http://127.0.0.1:9/v1")
        settings().update { it.copy(providers = it.providers.filterNot { p -> p.id == provider.id } + provider, assistants = it.assistants.filterNot { a -> a.id == assistant.id } + assistant) }
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
        assertEquals("audio/wav", voice.mimeType)
        assertEquals(100L, voice.durationMs)
        assertEquals(1644L, File(requireNotNull(Uri.parse(voice.localLocator).path)).length())
        assertTrue(recoverP14AttachmentPart(voice) is UIMessagePart.Audio)
        assertEquals("IN_FLIGHT", dao.pendingOutbox().single { it.messageId == localMessageId.toString() }.state)
        repository.acceptRikkaMessage("task4-offline", "ae800001-0000-4000-8000-000000000002", offlineMessage(), "ae800001-0000-4000-8000-000000000003", "ae800001-0000-4000-8000-000000000004")
        assertEquals(1, dao.listMessages(id).size)
        assertEquals(1, dao.pendingOutbox().count { it.messageId == localMessageId.toString() })
        assertEquals("task4-persisted-draft", P14CaptureDraftStore(context).read().text)
        val conversations: ConversationRepository = GlobalContext.get().get()
        val recovery = P14ChatRecovery(dao, conversations, settings())
        recovery.restoreAll()
        val visibleId = Uuid.parse("ae800001-0000-4000-8000-000000000002")
        val visible = requireNotNull(conversations.getConversationById(visibleId))
        assertEquals("ae800001-0000-4000-8000-000000000003", visible.assistantId.toString())
        assertEquals(1, visible.messageNodes.flatMap { it.messages }.count { it.id == localMessageId })
        recovery.restoreAll()
        assertEquals(visible, conversations.getConversationById(visibleId))
        evidence("verifyOffline PASS after external force-stop: no duplicate accepted mutation, IN_FLIGHT retry eligible, VOICE source and draft retained")
    }

    private suspend fun pair(): P14RuntimeConnection {
        val base = requireNotNull(args.getString("p14BaseUrl"))
        val token = args.getString("p14SetupToken") ?: requireNotNull(args.getString("p14OwnerToken"))
        require(token in setOf("task4-owner-synthetic-only", "task4-setup-synthetic-only")) { "Synthetic fixture only" }
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

    /** Intentionally interrupted by the external driver after the fsynced marker. */
    @Test fun bootstrapCommitWindow() {
        runBlocking {
            val database=P14ProductDatabase.get(context)
            database.openHelper.writableDatabase
            assertEquals(0L,product().currentCursor())
            assertTrue(database.dao().directoryPage(1,0).isEmpty())
            val bootstrap=context.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE)
            assertNull(bootstrap.getString("snapshot",null))
            val lock=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("rikkahub-p14-product.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            lock.beginTransactionNonExclusive()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            try {
                pair()
                scope.launch { runCatching { product().replayActive() } }
                val deadline=android.os.SystemClock.elapsedRealtime()+15000
                while(bootstrap.getString("snapshot",null)==null && android.os.SystemClock.elapsedRealtime()<deadline) delay(10)
                assertNotNull("Real bootstrap HTTP must have durably saved pagination before Room",bootstrap.getString("snapshot",null))
                assertTrue(bootstrap.getBoolean("more",false))
                assertEquals(0L,lockedCursor(lock))
                assertEquals(0,lockedEntryCount(lock))
                writeWindowMarker("task4-bootstrap-window-ready","FIRST_PAGE_PREFS_WITH_CURSOR_ZERO")
                delay(60000)
                error("External driver did not force-stop at the verified bootstrap window")
            } finally { lock.endTransaction();lock.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }

    @Test fun verifyBootstrapWindow() = runBlocking {
        assertEquals("FIRST_PAGE_PREFS_WITH_CURSOR_ZERO",File(context.filesDir,"task4-bootstrap-window-ready").readText())
        val start=android.os.SystemClock.elapsedRealtime()
        pair()
        product().replayActive()
        val rows=P14ProductDatabase.get(context).dao().directoryPage(10000,0)
        assertTrue("Cold recovery must not preload all 1200 fixture Entries",rows.size in 1..50)
        assertTrue(product().currentCursor()>0)
        assertTrue(context.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE).getBoolean("more",false))
        File(context.filesDir,"task4-bootstrap-first-page-metrics.json").writeText("""{"rows":${rows.size},"elapsed_ms":${android.os.SystemClock.elapsedRealtime()-start},"cursor":${product().currentCursor()}}""")
        evidence("verifyBootstrapWindow PASS: external force-stop after actual prefs commit/before Room commit; restart refetched bounded first page, cursor and older-page locator recovered")
    }

    /** Hold the actual older-page write after its real network response, then external kill. */
    @Test fun olderPageCommitWindow() {
        runBlocking {
            val bootstrap=context.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE)
            val before=requireNotNull(bootstrap.getString("before_id",null))
            assertTrue(product().currentCursor()>0)
            val database=P14ProductDatabase.get(context)
            val baseline=database.dao().directoryPage(10000,0).size
            assertTrue(baseline in 1..50)
            val lock=android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("rikkahub-p14-product.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            lock.beginTransactionNonExclusive()
            val fetched=java.util.concurrent.atomic.AtomicBoolean(false)
            val fetchHttp=http.newBuilder().addInterceptor { chain ->
                val response=chain.proceed(chain.request())
                if(chain.request().url.encodedPath.endsWith("/bootstrap/entries") && chain.request().url.queryParameter("before_entry_id")==before) {
                    check(response.isSuccessful)
                    val contentType=response.body?.contentType()
                    val text=requireNotNull(response.body).string()
                    check(Json.parseToJsonElement(text).jsonObject["items"]?.toString()!="[]")
                    fetched.set(true)
                    response.newBuilder().body(text.toResponseBody(contentType)).build()
                } else response
            }.build()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            try {
                scope.launch { runCatching { P14ProductRepository(context,fetchHttp).directoryPage(baseline,50) } }
                val deadline=android.os.SystemClock.elapsedRealtime()+15000
                while(!fetched.get() && android.os.SystemClock.elapsedRealtime()<deadline) delay(10)
                assertTrue("Must receive the real older-page response before kill",fetched.get())
                assertEquals(before,bootstrap.getString("before_id",null))
                assertEquals(baseline,lockedEntryCount(lock))
                context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE).edit().putString("older-page-before-id",before).putInt("older-page-baseline",baseline).commit()
                writeWindowMarker("task4-older-page-window-ready","OLDER_PAGE_FETCHED_WITHOUT_COMMIT")
                delay(60000)
                error("External driver did not force-stop at verified older-page window")
            } finally { lock.endTransaction();lock.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        }
    }

    @Test fun verifyOlderPageWindow() = runBlocking {
        assertEquals("OLDER_PAGE_FETCHED_WITHOUT_COMMIT",File(context.filesDir,"task4-older-page-window-ready").readText())
        val prefs=context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE)
        val baseline=prefs.getInt("older-page-baseline",-1)
        val bootstrap=context.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE)
        assertEquals(prefs.getString("older-page-before-id",null),bootstrap.getString("before_id",null))
        val recovered=product().directoryPage(baseline,50)
        assertEquals(50,recovered.size)
        assertEquals(50,recovered.map { it.entryId }.toSet().size)
        assertNotEquals(prefs.getString("older-page-before-id",null),bootstrap.getString("before_id",null))
        // Coverage may need one additional bounded page to withhold the boundary millisecond.
        assertTrue(P14ProductDatabase.get(context).dao().directoryPage(10000,0).size<=150)
        evidence("verifyOlderPageWindow PASS: actual older-page response lost before Room commit; restart retried original locator with no missing/duplicate page and bounded cache")
    }

    @Test fun scaleDirectoryPaging() = runBlocking {
        val seen=mutableSetOf<String>()
        var offset=0
        val start=android.os.SystemClock.elapsedRealtime()
        repeat(80) {
            val page=product().directoryPage(offset,100)
            assertTrue(page.size<=100)
            page.forEach { item -> assertTrue("Duplicate Entry while paging",seen.add(item.entryId)) }
            offset+=page.size
            if(page.isEmpty() && !context.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE).getBoolean("more",false)) return@repeat
        }
        assertTrue("All pre-seeded 1200 historical Entries must remain discoverable",seen.size>=1200)
        File(context.filesDir,"task4-directory-scale-metrics.json").writeText("""{"distinct_entries":${seen.size},"elapsed_ms":${android.os.SystemClock.elapsedRealtime()-start}}""")
        evidence("scaleDirectoryPaging PASS: >=1200 Entries lazily enumerated without duplicate identities; bounded initial-page metrics are recorded separately")
    }

    private fun lockedCursor(database:android.database.sqlite.SQLiteDatabase):Long = database.rawQuery("SELECT COALESCE(MAX(last_sync_seq),0) FROM p14_product_sync_state",null).use { it.moveToFirst();it.getLong(0) }
    private fun lockedEntryCount(database:android.database.sqlite.SQLiteDatabase):Int = database.rawQuery("SELECT COUNT(*) FROM p14_conversations",null).use { it.moveToFirst();it.getInt(0) }
    private fun writeWindowMarker(name:String,value:String) {
        File(context.filesDir,name).outputStream().use { it.write(value.toByteArray());it.fd.sync() }
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

    @Test fun materializeRemoteMedia() = runBlocking {
        val entryId=requireNotNull(args.getString("p14MediaEntryId"))
        val messageId=requireNotNull(args.getString("p14MediaMessageId"))
        product().loadEntryHistory(entryId)
        val row=P14ProductDatabase.get(context).dao().attachmentsForMessage(messageId).single {
            it.remoteAttachmentId==args.getString("p14MediaAttachmentId")
        }
        assertEquals("VOICE",row.kind)
        assertEquals("audio/wav",row.mimeType)
        val uri=product().materializeAttachment(row.localAttachmentId)
        verifyMediaFile(uri)
        context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE).edit()
            .putString("remote-media-local-id",row.localAttachmentId).putString("remote-media-locator",uri.toString()).commit()
        product().loadEntryHistory(entryId)
        val replayed=requireNotNull(P14ProductDatabase.get(context).dao().attachment(row.localAttachmentId))
        assertEquals(uri.toString(),replayed.localLocator)
        assertNotNull(recoverP14AttachmentPart(replayed))
        evidence("materializeRemoteMedia PASS: second-device authenticated binary download size/hash checked, VOICE identity retained, history refresh retains managed locator; standard Server attachment path, not P20 upload")
    }

    @Test fun verifyRemoteMedia() = runBlocking {
        val prefs=context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE)
        val id=requireNotNull(prefs.getString("remote-media-local-id",null))
        val expected=requireNotNull(prefs.getString("remote-media-locator",null))
        val row=requireNotNull(P14ProductDatabase.get(context).dao().attachment(id))
        assertEquals(expected,row.localLocator)
        val uri=product().materializeAttachment(id)
        assertEquals(expected,uri.toString())
        verifyMediaFile(uri)
        val restored=recoverP14AttachmentPart(row)
        assertTrue(restored is UIMessagePart.Audio)
        assertEquals("VOICE",durableP14AudioKind(restored as UIMessagePart.Audio))
        evidence("verifyRemoteMedia PASS after external force-stop: app-owned remote binary and VOICE recovered without old device or reupload")
    }

    private fun verifyMediaFile(uri:Uri) {
        val file=File(requireNotNull(uri.path))
        assertTrue(file.canonicalPath.startsWith(File(context.filesDir,"p14_remote_media").canonicalPath+"/"))
        assertEquals(requireNotNull(args.getString("p14MediaByteSize")).toLong(),file.length())
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(8192);while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
        assertEquals(args.getString("p14MediaSha256"),digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test fun uploaderMediaContract() = runBlocking {
        val runtime = requireNotNull(P14RuntimeCredentialStore(context).activeConnection())
        val key = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(java.security.SecureRandom()).generatePublicKey().encoded
        val encoded = android.util.Base64.encodeToString(key, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val captured = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val intercepted = http.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.encodedPath.endsWith("/remote-attachments/capabilities") -> Response.Builder().request(request)
                    .protocol(Protocol.HTTP_1_1).code(200).message("Synthetic public key only")
                    .body("""{"recipient_key":{"key":"$encoded"}}""".toResponseBody("application/json".toMediaType())).build()
                request.url.encodedPath.endsWith("/remote-attachments") -> {
                    val buffer = okio.Buffer()
                    requireNotNull(request.body).writeTo(buffer)
                    synchronized(captured) { captured += Json.parseToJsonElement(buffer.readUtf8()).jsonObject }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503)
                        .message("Intentional contract capture boundary")
                        .body("Synthetic contract capture; no broker upload performed".toResponseBody()).build()
                }
                else -> chain.proceed(request)
            }
        }.build()
        val source = P14ProductDatabase.get(context).dao().attachmentsForMessage(localMessageId.toString()).single()
        for (kind in listOf("VOICE", "AUDIO")) {
            val result = runCatching { P14DurableAttachmentUploader(context, intercepted).ensureUploaded(runtime, source.copy(localAttachmentId = "contract-$kind-${source.localAttachmentId}", kind = kind, state = "PENDING", remoteAttachmentId = null)) }
            assertEquals(503, (result.exceptionOrNull() as? P14RemoteHttpException)?.status)
        }
        assertEquals(2, captured.size)
        assertEquals("voice_message", captured[0]["media_role"]?.jsonPrimitive?.content)
        assertFalse(captured[1].containsKey("media_role"))
        File(context.filesDir, "task4-remote-contracts.json").writeText(JsonArray(captured).toString())
        evidence("uploaderMediaContract PASS: actual encrypted upload POST bodies captured for VOICE/AUDIO; exported for actual Server Pydantic validation. Synthetic recipient/503 boundary; NOT P20 upload acceptance")
    }

    @Test fun seedInteractionOffline() = runBlocking {
        val notificationId = requireNotNull(args.getString("p14NotificationId"))
        val connection = requireNotNull(P14RuntimeCredentialStore(context).activeConnection())
        val interaction = requireNotNull(remote().interaction(notificationId))
        assertEquals("PENDING", interaction.status)
        // Suspend only this synthetic installation's active delivery pointer. The token remains
        // encrypted in Android Keystore-backed storage. No network outage is claimed by this phase.
        context.getSharedPreferences("task4-evidence", Context.MODE_PRIVATE).edit()
            .putString("paused-provider", connection.providerId).putString("paused-base", connection.baseUrl).commit()
        context.getSharedPreferences("p14-rikkahub-runtime", Context.MODE_PRIVATE).edit()
            .remove("active_provider").remove("active_base").commit()
        remote().queueInteraction(notificationId, "once")
        val dao = P14RemoteDatabase.get(context).dao()
        val op = dao.pendingOperations().single { it.notificationId == notificationId && it.type == "INTERACTION" }
        dao.setOperationState(op.operationId, "IN_FLIGHT")
        context.getSharedPreferences("task4-evidence", Context.MODE_PRIVATE).edit().putString("interaction-operation", op.operationId).commit()
        assertEquals("RESPONDED", dao.interaction(interaction.interactionId)?.status)
        evidence("seedInteractionOffline PASS: accepted response and stable IN_FLIGHT operation persisted with delivery pointer suspended; external force-stop required")
    }

    @Test fun verifyInteractionRecovery() = runBlocking {
        val prefs = context.getSharedPreferences("task4-evidence", Context.MODE_PRIVATE)
        val operationId = requireNotNull(prefs.getString("interaction-operation", null))
        val dao = P14RemoteDatabase.get(context).dao()
        val op = dao.pendingOperations().single { it.operationId == operationId }
        assertEquals("IN_FLIGHT", op.state)
        P14RuntimeCredentialStore(context).markActive(requireNotNull(prefs.getString("paused-provider", null)), requireNotNull(prefs.getString("paused-base", null)))
        assertTrue(remote().processPendingActive())
        assertFalse(dao.pendingOperations().any { it.operationId == operationId })
        val interaction = requireNotNull(remote().interaction(requireNotNull(args.getString("p14NotificationId"))))
        assertEquals("RESPONDED", interaction.status)
        assertEquals("once", interaction.response)
        evidence("verifyInteractionRecovery PASS after external force-stop: persisted stable response operation reattempted, authoritative RESPONDED once")
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

    @Test fun actionDecisionsAndQuickAction() = runBlocking {
        val entryId=requireNotNull(args.getString("p14MediaEntryId"))
        val conversationId=requireNotNull(args.getString("p14MediaConversationId"))
        val repository=product()
        repository.hydrateEntryState(entryId,conversationId)
        val cases=listOf("confirm" to "approved_pending_execution","supplement" to "needs_reassessment","skip" to "skipped","cancel" to "cancelled")
        for((decision,status) in cases) {
            val key="p14Action"+decision.replaceFirstChar { it.uppercase() }
            val actionId=requireNotNull(args.getString(key))
            val op=remote().queueActionDecision(actionId,decision,if(decision=="supplement") "task4-Android-extra-context" else null)
            assertTrue(remote().processPendingActive())
            assertEquals("ACKED",operationState(op))
            repository.replayActive()
            repository.hydrateEntryState(entryId,conversationId)
            assertEquals(status,P14ProductDatabase.get(context).dao().action(actionId)?.status)
        }
        val own=repository.directoryPage(0,100).single { it.bodyText=="task4-device-A" }
        val op=remote().quickActionActive(own.conversationId,"content.accept")
        val db=P14RemoteDatabase.get(context)
        val payload=db.openHelper.readableDatabase.query("SELECT payload FROM p14_operations WHERE operation_id=?",arrayOf(op)).use { cursor ->
            assertTrue(cursor.moveToFirst());Json.parseToJsonElement(cursor.getString(0)).jsonObject
        }
        val actionId=requireNotNull(payload["action_id"]?.jsonPrimitive?.content)
        repeat(2) {
            db.dao().setOperationState(op,"IN_FLIGHT")
            assertTrue(remote().processPendingActive())
            assertEquals("ACKED",operationState(op))
        }
        repository.replayActive()
        repository.hydrateEntryState(own.entryId,own.conversationId)
        assertEquals(1,repository.actions(own.conversationId).first().count { it.actionId==actionId })
        evidence("actionDecisionsAndQuickAction PASS: four native durable decision paths -> canonical server statuses; stable QuickAction payload retries IN_FLIGHT twice and one authoritative Action. No tool/Hermes execution claimed")
    }

    @Test fun competingDeviceActionConflict() = runBlocking {
        val actionId=requireNotNull(args.getString("p14ActionConfirm"))
        val op=remote().queueActionDecision(actionId,"cancel")
        remote().processPendingActive()
        assertEquals("CONFLICT",operationState(op))
        evidence("competingDeviceActionConflict PASS: other device cannot silently overwrite accepted once-only decision; local operation marked CONFLICT, not ACKED")
    }

    private fun operationState(id:String):String? = P14RemoteDatabase.get(context).openHelper.readableDatabase
        .query("SELECT state FROM p14_operations WHERE operation_id=?",arrayOf(id)).use { cursor ->
            if(cursor.moveToFirst()) cursor.getString(0) else null
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
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION))
            assertTrue("LOCATION" in P14CapabilityRegistry.granted(context))
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
        remote().refreshActive()
        args.getString("p14NotificationId")?.let {
            assertEquals("RESPONDED", remote().interaction(it)?.status)
            assertEquals("once", remote().interaction(it)?.response)
        }
        val state = requireNotNull(repository.conversation(entry.conversationId))
        assertTrue(state.pinned && state.archived)
        assertTrue(repository.messages(entry.conversationId).first().any { it.tombstone })
        for (view in listOf("todo", "idea", "research", "execute")) {
            assertTrue(repository.directoryPage(0, 100, view).any { it.entryId == entry.entryId })
        }
        evidence("deviceAVerify PASS: A→B→A converged same conversation, deduped replay, durable cursor=$cursor")
    }
    /** Revoke this real emulator credential; retain its encrypted local copy to test rejection. */
    @Test fun selfRevoke():Unit = runBlocking {
        val store=P14RuntimeCredentialStore(context)
        val runtime=requireNotNull(store.activeConnection())
        require(args.getString("p14BaseUrl")=="http://10.0.2.2:8014/v1" && runtime.baseUrl=="http://10.0.2.2:8014/v1")
        val repository=product()
        assertTrue(repository.replayActive())
        val conversation=repository.directoryPage(0,100).single { it.bodyText=="task4-device-A" }
        val cursor=repository.currentCursor()
        assertFalse(repository.messages(conversation.conversationId).first().any { it.bodyText==AFTER_REVOKE_TEXT })
        val request=Request.Builder().url(runtime.baseUrl+"/devices/"+store.deviceId()+"/revoke")
            .header("Authorization","Bearer "+runtime.bearerToken)
            .post("".toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            assertEquals(200,response.code)
            val body=Json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
            assertEquals(store.deviceId(),body["device_id"]?.jsonPrimitive?.content)
            assertEquals("false",body["active"]?.jsonPrimitive?.content)
        }
        // Do not clear credentials or edit active-provider prefs: test the actual revoked bearer.
        assertNotNull(store.activeConnection())
        assertRevokedCall("product replay") { repository.replayActive() }
        assertRevokedCall("durable Inbox refresh") { remote().refreshActive() }
        assertEquals(cursor,repository.currentCursor())
        assertTrue(context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE).edit()
            .putLong("revoked-A-cursor",cursor)
            .putString("revoked-A-conversation",conversation.conversationId)
            .putString("revoked-A-device",store.deviceId()).commit())
        evidence("selfRevoke PASS: actual emulator device self-revoked via authenticated Server route; existing encrypted credential retained and native replay/Inbox refused")
    }

    /** Run on the other emulator after A's revoke; creates new protected state with B's key. */
    @Test fun writeAfterOtherDeviceRevoke():Unit = runBlocking {
        val runtime=requireNotNull(P14RuntimeCredentialStore(context).activeConnection())
        require(args.getString("p14BaseUrl")=="http://10.0.2.2:8014/v1" && runtime.baseUrl=="http://10.0.2.2:8014/v1")
        http.newCall(Request.Builder().url(runtime.baseUrl+"/models")
            .header("Authorization","Bearer "+runtime.bearerToken).build()).execute().use { response ->
            assertEquals(200,response.code)
            assertTrue(requireNotNull(response.body).string().contains("p14-hermes"))
        }
        val repository=product()
        val entry=repository.directoryPage(0,100).single { it.bodyText=="task4-device-A" }
        val before=repository.currentCursor()
        val accepted=repository.acceptEntryContinuation(entry.conversationId,AFTER_REVOKE_TEXT)
        assertTrue(repository.syncAndFlushActive())
        assertFalse(P14ProductDatabase.get(context).dao().pendingOutbox().any { it.messageId==accepted.messageId })
        assertTrue(repository.currentCursor()>before)
        assertTrue(repository.messages(entry.conversationId).first().any { it.messageId==accepted.messageId && it.bodyText==AFTER_REVOKE_TEXT })
        assertTrue(remote().refreshActive())
        evidence("writeAfterOtherDeviceRevoke PASS: actual B model discovery, protected message commit/replay and Inbox remain healthy after A revoke")
    }

    /** Run on A after an external force-stop, after B has committed its new message. */
    @Test fun verifyRevokedDeviceCannotRecoverNewState():Unit = runBlocking {
        val store=P14RuntimeCredentialStore(context)
        val runtime=requireNotNull(store.activeConnection())
        require(args.getString("p14BaseUrl")=="http://10.0.2.2:8014/v1" && runtime.baseUrl=="http://10.0.2.2:8014/v1")
        val prefs=context.getSharedPreferences("task4-evidence",Context.MODE_PRIVATE)
        val conversationId=requireNotNull(prefs.getString("revoked-A-conversation",null))
        val cursor=prefs.getLong("revoked-A-cursor",-1L)
        assertTrue(cursor>0)
        assertEquals(prefs.getString("revoked-A-device",null),store.deviceId())
        assertRevokedCall("product replay after restart") { product().replayActive() }
        assertRevokedCall("Inbox refresh after restart") { remote().refreshActive() }
        val attachment=requireNotNull(args.getString("p14MediaAttachmentId"))
        for(path in listOf("models","remote-manifest","sync/events","notifications",
            "bootstrap/entries?limit=1","attachments/"+attachment+"/content")) {
            http.newCall(Request.Builder().url(runtime.baseUrl+"/"+path)
                .header("Authorization","Bearer "+runtime.bearerToken).build()).execute().use { response ->
                assertEquals("Revoked device must be refused for "+path,401,response.code)
            }
        }
        assertEquals(cursor,product().currentCursor())
        assertFalse("Revoked A must not receive B's new protected message",
            product().messages(conversationId).first().any { it.bodyText==AFTER_REVOKE_TEXT })
        assertNotNull("Cached accepted user data must remain available locally",
            P14ProductDatabase.get(context).dao().conversation(conversationId))
        evidence("verifyRevokedDeviceCannotRecoverNewState PASS: retained credential denied on native replay/Inbox/models/Manifest/media after process restart; cursor unchanged, B's post-revoke message absent, cached local data preserved")
    }

    private suspend fun assertRevokedCall(label:String,action:suspend ()->Any?) {
        val error=runCatching { action() }.exceptionOrNull()
        assertTrue(label+" must reject with HTTP 401",error is P14RemoteHttpException && error.status==401)
    }

    private companion object {
        const val AFTER_REVOKE_TEXT="task4-B-new-protected-state-after-A-revoke"
    }


}
