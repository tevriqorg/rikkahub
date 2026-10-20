package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Real Room + real OkHttp interceptor; all data/prefs/database are scoped to this test. */
@RunWith(AndroidJUnit4::class)
class P14DirectoryPagingInstrumentedTest {
    @Test fun coldEntriesDoNotJumpAcrossUnloadedHistory() = runBlocking {
        withFixture(sameMillisecond=false) { fixture ->
            val before=HashMap(fixture.bootstrap.all)
            val cursor=fixture.repository.currentCursor()
            // Many unrelated deep links must not be mistaken for a contiguous loaded prefix.
            for (row in fixture.rows.takeLast(90)) {
                assertEquals(row.entryId,fixture.repository.ensureConversationForId(row.conversationId)?.entryId)
                assertEquals(before,fixture.bootstrap.all)
                assertEquals(cursor,fixture.repository.currentCursor())
            }
            assertNull(fixture.repository.ensureConversationForId("missing-synthetic-conversation"))
            assertEquals(before,fixture.bootstrap.all)
            fixture.addCurrentLocalEntry()
            val seen=fixture.enumerate()
            assertEquals(listOf("local-current-entry")+fixture.rows.map { it.entryId },seen)
            assertEquals(seen.size,seen.toSet().size)
            assertEquals(cursor,fixture.repository.currentCursor())
            assertTrue(fixture.maxRequestedPage<=50)
            assertTrue(fixture.olderRequests>0)
        }
    }

    @Test fun sameMillisecondBoundaryNeverDropsOrRepeatsEntries() = runBlocking {
        withFixture(sameMillisecond=true) { fixture ->
            fixture.rows.takeLast(90).forEach { fixture.repository.ensureConversationForId(it.conversationId) }
            val seen=fixture.enumerate()
            assertEquals(fixture.rows.map { it.entryId },seen)
            assertEquals(180,seen.toSet().size)
            assertFalse(fixture.bootstrap.getBoolean("more",true))
            assertEquals(3,fixture.olderRequests)
            assertTrue(fixture.maxRequestedPage<=50)
        }
    }

    @Test fun stalledContinuationFailsWithoutFalseEndOfHistory() = runBlocking {
        withFixture(sameMillisecond=false) { fixture ->
            val before=HashMap(fixture.bootstrap.all)
            fixture.stall=true
            assertTrue(runCatching { fixture.repository.directoryPage(0,100) }.isFailure)
            assertEquals(before,fixture.bootstrap.all)
            assertTrue(fixture.bootstrap.getBoolean("more",false))
            assertEquals(1,fixture.olderRequests)
        }
    }

    private suspend fun withFixture(sameMillisecond:Boolean, block:suspend (Fixture)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val context=ScopedContext(base,"task4-directory-"+UUID.randomUUID())
        val database=Room.inMemoryDatabaseBuilder(context,P14ProductDatabase::class.java).build()
        try {
            val fixture=Fixture(context,database,sameMillisecond)
            fixture.repository.replay(fixture.connection)
            assertEquals(123L,fixture.repository.currentCursor())
            assertEquals(50,database.dao().directoryPage(1000,0).size)
            block(fixture)
        } finally {
            database.close()
            context.removeOwnedPreferences()
        }
    }

    private class ScopedContext(base:Context, private val prefix:String):ContextWrapper(base) {
        private val names=mutableSetOf<String>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            val scoped="$prefix-$name"
            names.add(scoped)
            return baseContext.getSharedPreferences(scoped,mode)
        }
        fun removeOwnedPreferences() {
            names.forEach {
                baseContext.getSharedPreferences(it,Context.MODE_PRIVATE).edit().clear().commit()
                baseContext.deleteSharedPreferences(it)
            }
        }
    }

    private data class Row(val entryId:String,val conversationId:String,val createdAt:String) {
        fun json()=buildJsonObject {
            put("entry_id",entryId);put("conversation_id",conversationId)
            put("body_text","Synthetic directory $entryId");put("created_at",createdAt)
        }
    }

    private class Fixture(val context:Context,val database:P14ProductDatabase,sameMillisecond:Boolean) {
        val rows=(0 until 180).map { n ->
            val id="directory-"+n.toString().padStart(4,'0')
            Row(id,"conversation-$id",
                Instant.parse("2026-10-20T00:00:00Z").plusMillis(if(sameMillisecond) 0 else n.toLong()).toString())
        }.reversed()
        val connection=P14RuntimeConnection("http://127.0.0.1:9/v1","task4-directory-synthetic-only","task4-directory-provider")
        val bootstrap=context.getSharedPreferences("p14-entry-bootstrap",Context.MODE_PRIVATE)
        var maxRequestedPage=0
        var olderRequests=0
        var stall=false
        private val snapshot="2026-10-20T12:00:00Z"
        private val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            check(request.header("Authorization")=="Bearer "+connection.bearerToken)
            val url=request.url
            val body=when(url.encodedPath) {
                "/v1/bootstrap/entries" -> {
                    val selected=url.queryParameter("conversation_id")
                    val limit=requireNotNull(url.queryParameter("limit")).toInt()
                    check(limit in 1..50)
                    maxRequestedPage=maxOf(maxRequestedPage,limit)
                    val before=url.queryParameter("before_entry_id")
                    val remaining=if(selected!=null) rows.filter { it.conversationId==selected }
                        else if(before!=null) {
                            olderRequests++
                            val index=rows.indexOfFirst { it.entryId==before }
                            check(index>=0)
                            rows.drop(index+1)
                        } else rows
                    val page=remaining.take(limit)
                    val more=selected==null && remaining.size>limit
                    buildJsonObject {
                        put("snapshot_at",snapshot);put("watermark",123)
                        put("items",buildJsonArray { page.forEach { add(it.json()) } })
                        put("has_more",more)
                        if(more) {
                            put("next_before_created_at",if(stall && before!=null) requireNotNull(url.queryParameter("before_created_at")) else page.last().createdAt)
                            put("next_before_entry_id",if(stall && before!=null) before else page.last().entryId)
                        } else {
                            put("next_before_created_at",JsonNull);put("next_before_entry_id",JsonNull)
                        }
                    }.toString()
                }
                "/v1/sync/events" -> """{"events":[],"next_after":123,"watermark":123,"has_more":false}"""
                else -> error("Unexpected fixture request path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository=P14ProductRepository(context,http,database)
        init {
            P14RuntimeCredentialStore(context).apply {
                save(connection.providerId,connection.baseUrl,connection.bearerToken)
                markActive(connection.providerId,connection.baseUrl)
            }
            check(context.getSharedPreferences("p14-rikkahub-runtime",Context.MODE_PRIVATE).edit().commit())
        }
        suspend fun addCurrentLocalEntry() {
            val time=Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()
            database.dao().upsertEntry(P14EntryEntity("local-current-entry","Local synthetic pending input",time))
            database.dao().upsertConversation(P14ProductConversationEntity("local-current-conversation",
                "local-current-entry","local-directory-fixture","local-owned-conversation",time))
        }
        suspend fun enumerate():List<String> {
            val result=mutableListOf<String>()
            repeat(20) {
                val page=repository.directoryPage(result.size,20)
                if(page.isEmpty()) return result
                page.forEach { item -> assertFalse("Directory repeated "+item.entryId,result.contains(item.entryId));result.add(item.entryId) }
            }
            error("Bounded synthetic history did not terminate")
        }
    }
}
