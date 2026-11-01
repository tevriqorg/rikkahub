package me.rerere.rikkahub.data.remote.p14

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.Uuid

/** Native RikkaHub UUID must never replace P14 Server's original Entry session. */
@RunWith(AndroidJUnit4::class)
class P14NativeConversationBindingInstrumentedTest {
    @Test fun oldEntryWithoutLocalIdBindsBeforeNativeTurnWithoutCreatingDuplicateEntry(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Synthetic binding fixture may run only on a disposable emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
        )
        val dbName = "p14-native-binding-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, P14ProductDatabase::class.java, dbName).build()
        try {
            val externalSession = "rk-session-${UUID.randomUUID()}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("rikkahub\u0000$externalSession".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(40)
            val entryId = "rk-e-$digest"
            val conversationId = "rk-c-$digest"
            val nativeId = Uuid.random().toString()
            val dao = db.dao()
            dao.upsertEntry(P14EntryEntity(entryId, "Existing saved source", 1000L))
            dao.upsertConversation(P14ProductConversationEntity(
                conversationId = conversationId,
                entryId = entryId,
                externalSessionId = externalSession,
                localConversationId = null,
                createdAtEpochMs = 1000L,
            ))
            val product = P14ProductRepository(context, OkHttpClient(), db)
            assertEquals(null, product.conversationForLocal(nativeId))
            product.bindNativeConversationId(conversationId, nativeId)
            product.bindNativeConversationId(conversationId, nativeId)
            assertEquals(externalSession, product.conversationForLocal(nativeId)?.externalSessionId)
            val userMessage = UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("Continue discussing original Entry")),
            )
            val accepted = product.acceptRikkaMessage(
                externalSessionId = requireNotNull(product.conversationForLocal(nativeId)).externalSessionId,
                localConversationId = nativeId,
                message = userMessage,
                assistantId = null,
                modelId = null,
            )
            assertEquals(entryId, accepted.entryId)
            assertEquals(conversationId, accepted.conversationId)
            assertEquals("Existing saved source", dao.entry(entryId)?.bodyText)
            val operation = dao.pendingOutbox().single { it.messageId == userMessage.id.toString() }
            assertEquals("SEND_MESSAGE", operation.operationType)
            assertEquals(externalSession, operation.externalSessionId)
            assertFalse(dao.pendingOutbox().any {
                it.messageId == userMessage.id.toString() && it.operationType == "CREATE_ENTRY_WITH_MESSAGE"
            })
            val rejected = runCatching {
                product.bindNativeConversationId(conversationId, Uuid.random().toString())
            }
            assertTrue("Rebinding an existing Entry to another native chat must fail", rejected.isFailure)
            assertEquals(nativeId, dao.conversation(conversationId)?.localConversationId)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
