package me.rerere.rikkahub.data.remote.p14

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.uuid.Uuid

class P14RemoteControlClientTest {
    @Test
    fun `control plane resolves canonical ids and sends revision guarded mutations`() = runTest {
        val requests = mutableListOf<Request>()
        val responses = ArrayDeque(
            listOf(
                """{"entry_id":"entry-1","conversation_id":"conversation-1"}""",
                """{"revision":2,"body_text":"current text","deleted":false}""",
                """{"revision":3}""",
                """{"revision":3}""",
            )
        )
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responses.removeFirst().toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val provider = ProviderSetting.OpenAI(
            apiKey = "test-token",
            baseUrl = "https://p14.example/v1",
        )
        val messageId = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val client = P14RemoteControlClient(http)

        val binding = client.resolveSession(provider, "local-session")
        val state = client.getMessageState(provider, binding.conversationId, messageId)
        val editedRevision = client.editMessage(
            provider, binding.conversationId, messageId, state.revision, "edited text"
        )
        val deletedRevision = client.deleteMessage(
            provider, binding.conversationId, messageId, editedRevision
        )

        assertEquals("conversation-1", binding.conversationId)
        assertEquals(2, state.revision)
        assertEquals("current text", state.bodyText)
        assertEquals(3, editedRevision)
        assertEquals(3, deletedRevision)
        assertEquals("Bearer test-token", requests.first().header("Authorization"))
        assertEquals("local-session", requests.first().header("X-Session-ID"))
        assertTrue(requests[1].url.encodedPath.endsWith("/v1/conversations/conversation-1/messages/$messageId"))

        val editBody = Json.parseToJsonElement(requests[2].bodyText()).jsonObject
        assertEquals("2", editBody["base_revision"]?.jsonPrimitive?.content)
        assertEquals("edited text", editBody["text"]?.jsonPrimitive?.content)
        val deleteBody = Json.parseToJsonElement(requests[3].bodyText()).jsonObject
        assertEquals("3", deleteBody["base_revision"]?.jsonPrimitive?.content)
    }

    @Test
    fun `quick action stays structured and generic`() = runTest {
        val requests = mutableListOf<Request>()
        val responseJson = """{
            "action_id":"33333333-3333-4333-8333-333333333333",
            "action_type":"content.accept",
            "sync_seq":42,
            "deduplicated":false
        }""".trimIndent()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(201)
                .message("Created")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val provider = ProviderSetting.OpenAI(
            apiKey = "test-token",
            baseUrl = "https://p14.example/v1",
        )
        val client = P14RemoteControlClient(http)
        val actionId = Uuid.parse("33333333-3333-4333-8333-333333333333")
        val operationId = Uuid.parse("44444444-4444-4444-8444-444444444444")
        val targetId = Uuid.parse("55555555-5555-4555-8555-555555555555")

        val receipt = client.createQuickAction(
            provider = provider,
            conversationId = "conversation-1",
            actionType = "content.accept",
            targetMessageId = targetId,
            payload = buildJsonObject { put("revision_id", "rev-2") },
            clientOperationId = operationId,
            actionId = actionId,
        )

        assertEquals(actionId.toString(), receipt.actionId)
        assertEquals("content.accept", receipt.actionType)
        assertEquals(42L, receipt.syncSeq)
        val body = Json.parseToJsonElement(requests.single().bodyText()).jsonObject
        assertEquals(operationId.toString(), body["client_operation_id"]?.jsonPrimitive?.content)
        assertEquals(actionId.toString(), body["action_id"]?.jsonPrimitive?.content)
        assertEquals("content.accept", body["action_type"]?.jsonPrimitive?.content)
        assertEquals(targetId.toString(), body["target_message_id"]?.jsonPrimitive?.content)
        assertEquals("rev-2", body["payload"]?.jsonObject?.get("revision_id")?.jsonPrimitive?.content)
        assertTrue(requests.single().url.encodedPath.endsWith("/v1/conversations/conversation-1/quick-actions"))
    }

    @Test
    fun `conversation state stays server authoritative`() = runTest {
        val requests = mutableListOf<Request>()
        val responses = ArrayDeque(
            listOf(
                """{"conversation_id":"conversation-1","pinned":false,"archived":false,"revision":0}""",
                """{"conversation_id":"conversation-1","pinned":true,"archived":false,"revision":1,"sync_seq":9,"deduplicated":false}""",
            )
        )
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responses.removeFirst().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = ProviderSetting.OpenAI(apiKey = "test-token", baseUrl = "https://p14.example/v1")
        val client = P14RemoteControlClient(http)
        val initial = client.getConversationState(provider, "conversation-1")
        val updated = client.setConversationState(provider, "conversation-1", initial.revision, pinned = true)

        assertEquals(false, initial.pinned)
        assertEquals(true, updated.pinned)
        assertEquals(1, updated.revision)
        val body = Json.parseToJsonElement(requests[1].bodyText()).jsonObject
        assertEquals("0", body["base_revision"]?.jsonPrimitive?.content)
        assertEquals("true", body["pinned"]?.jsonPrimitive?.content)
    }

    @Test
    fun `activity list reads authoritative remote projection`() = runTest {
        val requests = mutableListOf<Request>()
        val responseJson = """[
            {
                "activity_id":"activity-1",
                "state":"running",
                "phase":"collecting sources",
                "progress_percent":25,
                "revision":2
            }
        ]""".trimIndent()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val provider = ProviderSetting.OpenAI(
            apiKey = "test-token",
            baseUrl = "https://p14.example/v1",
        )
        val activities = P14RemoteControlClient(http).listActivities(provider, "conversation-1")

        assertEquals(1, activities.size)
        assertEquals("activity-1", activities.single().activityId)
        assertEquals("running", activities.single().state)
        assertEquals("collecting sources", activities.single().phase)
        assertEquals(25, activities.single().progressPercent)
        assertEquals(2, activities.single().revision)
        assertTrue(requests.single().url.encodedPath.endsWith("/v1/conversations/conversation-1/activities"))
    }

    private fun Request.bodyText(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
