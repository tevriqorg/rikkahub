package me.rerere.rikkahub.data.db.migrations

import android.os.Build
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.remote.p14.P14ChatOutboxEntity
import me.rerere.rikkahub.data.remote.p14.P14EntryEntity
import me.rerere.rikkahub.data.remote.p14.P14ProductConversationEntity
import me.rerere.rikkahub.data.remote.p14.P14ProductDatabase
import me.rerere.rikkahub.data.remote.p14.P14ProductMessageEntity
import me.rerere.rikkahub.data.remote.p14.P14RuntimeCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Real Room open from v30 into v32, while an independent P14 v6 Outbox survives. */
@RunWith(AndroidJUnit4::class)
class P14Upgrade30To32PreservationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testDb = "p14-upgrade-30-to-32-fixture.db"

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun oldRikkaMessagesAndP14V6PendingOutboxSurviveMainDatabaseUpgrade(): Unit = runBlocking {
        assertTrue(
            "Never run synthetic migration fixture on a physical owner device",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
        )
        check(P14RuntimeCredentialStore(context).activeConnection() == null) {
            "Synthetic migration fixture must not have live Server credentials"
        }

        val suffix = Uuid.random().toString()
        val entryId = "entry-migration-$suffix"
        val conversationId = "conversation-migration-$suffix"
        val messageId = "message-migration-$suffix"
        val operationId = "operation-migration-$suffix"
        val productDbName = "p14-v6-upgrade-fixture-$suffix.db"

        // P14 Room is separate from upstream RikkaHub Room. The local pending
        // Outbox must remain durable and must not accidentally be sent by this test.
        val p14 = Room.databaseBuilder(
            context,
            P14ProductDatabase::class.java,
            productDbName,
        ).build()
        val p14Dao = p14.dao()
        p14Dao.upsertEntry(P14EntryEntity(entryId, "P14 durable source", 1_000L))
        p14Dao.upsertConversation(
            P14ProductConversationEntity(
                conversationId = conversationId,
                entryId = entryId,
                externalSessionId = conversationId,
                localConversationId = conversationId,
                createdAtEpochMs = 1_001L,
            ),
        )
        p14Dao.upsertMessage(
            P14ProductMessageEntity(
                messageId = messageId,
                conversationId = conversationId,
                role = "user",
                bodyText = "P14 unsent request",
                deliveryState = "PENDING",
                createdAtEpochMs = 1_002L,
            ),
        )
        p14Dao.insertOutbox(
            P14ChatOutboxEntity(
                clientOperationId = operationId,
                operationType = "SEND_MESSAGE",
                messageId = messageId,
                conversationId = conversationId,
                entryId = entryId,
                externalSessionId = conversationId,
                payloadJson = """{"text":"P14 unsent request"}""",
                state = "PENDING",
                createdAtEpochMs = 1_003L,
            ),
        )

        p14.close()

        // A real Room v30 fixture with both user and assistant history.
        // The v30->31 migration must backfill usage; v31->32 must retain it.
        helper.createDatabase(testDb, 30).apply {
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('historic-conv', 'historic-assistant', 'Historic discussion', '[]', 1000, 1000)",
            )
            execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (" +
                    "'historic-node', 'historic-conv', 0, " +
                    """'[{"role":"user","createdAt":"2026-10-30T10:00:00","parts":[]}, """ +
                    """{"role":"assistant","createdAt":"2026-10-30T10:00:30","usage":{"promptTokens":17,"completionTokens":3,"cachedTokens":2}}]'""" +
                    ", 0)",
            )
            close()
        }

        // This is the exact production migration-registration path, not a
        // hand-picked SQL migration or a destructive createDatabase(v32).
        val database = AppDatabaseFactory.create(context, testDb)
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(32, it.getInt(0))
            }
            sqlite.query("SELECT title FROM ConversationEntity WHERE id='historic-conv'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Historic discussion", it.getString(0))
            }
            sqlite.query(
                "SELECT prompt_tokens, completion_tokens, cached_tokens FROM usage_record " +
                    "WHERE conversation_id='historic-conv'",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(17, it.getInt(0))
                assertEquals(3, it.getInt(1))
                assertEquals(2, it.getInt(2))
                assertTrue(!it.moveToNext())
            }
        } finally {
            database.close()
        }

        // Open a second genuine Room connection to the existing independent
        // P14 v6 database: catches accidental database-name/schema overlap and
        // proves the pending Outbox is not silently acknowledged or deleted.
        val reloaded = Room.databaseBuilder(
            context,
            P14ProductDatabase::class.java,
            productDbName,
        ).build()
        try {
            reloaded.openHelper.writableDatabase.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(6, it.getInt(0))
            }
            val dao = reloaded.dao()
            assertEquals("P14 durable source", dao.entry(entryId)?.bodyText)
            assertEquals(conversationId, dao.conversation(conversationId)?.conversationId)
            assertEquals("P14 unsent request", dao.message(messageId)?.bodyText)
            val pending = dao.pendingOutbox().find { it.clientOperationId == operationId }
            assertNotNull("P14 Outbox must survive Room 30 to 32 upgrade", pending)
            assertEquals("PENDING", pending?.state)
        } finally {
            reloaded.close()
        }

        // The two independent test databases contain only synthetic fixtures.
        // This is not proof that an existing owner's installed APK can be
        // overwritten without migration or export/restore failures.
        assertTrue(context.deleteDatabase(productDbName))
        assertTrue(context.deleteDatabase(testDb))
    }
}
