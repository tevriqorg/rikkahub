package me.rerere.rikkahub.data.remote.p14

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun Request.bodyText(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
