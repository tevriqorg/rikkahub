package me.rerere.rikkahub.data.remote.p14

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.uuid.Uuid

internal class P14RemoteControlClient(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class SessionBinding(
        val entryId: String,
        val conversationId: String,
    )

    data class MessageState(
        val revision: Int,
        val bodyText: String,
        val deleted: Boolean,
    )

    suspend fun resolveSession(
        provider: ProviderSetting.OpenAI,
        externalSessionId: String,
    ): SessionBinding = withContext(Dispatchers.IO) {
        val body = request(
            provider = provider,
            method = "GET",
            path = "/remote-runtime/session",
            sessionId = externalSessionId,
        )
        SessionBinding(
            entryId = body.requireString("entry_id"),
            conversationId = body.requireString("conversation_id"),
        )
    }

    suspend fun getMessageState(
        provider: ProviderSetting.OpenAI,
        conversationId: String,
        messageId: Uuid,
    ): MessageState = withContext(Dispatchers.IO) {
        val body = request(
            provider = provider,
            method = "GET",
            path = "/conversations/$conversationId/messages/$messageId",
        )
        MessageState(
            revision = body["revision"]?.jsonPrimitive?.intOrNull
                ?: error("P14 message state omitted revision"),
            bodyText = body.requireString("body_text"),
            deleted = body["deleted"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: error("P14 message state omitted deleted"),
        )
    }

    suspend fun editMessage(
        provider: ProviderSetting.OpenAI,
        conversationId: String,
        messageId: Uuid,
        baseRevision: Int,
        text: String,
    ): Int = withContext(Dispatchers.IO) {
        val body = request(
            provider = provider,
            method = "POST",
            path = "/conversations/$conversationId/messages/$messageId/edit",
            body = buildJsonObject {
                put("client_operation_id", Uuid.random().toString())
                put("base_revision", baseRevision)
                put("text", text)
            },
        )
        body["revision"]?.jsonPrimitive?.intOrNull
            ?: error("P14 edit response omitted revision")
    }

    suspend fun deleteMessage(
        provider: ProviderSetting.OpenAI,
        conversationId: String,
        messageId: Uuid,
        baseRevision: Int,
    ): Int = withContext(Dispatchers.IO) {
        val body = request(
            provider = provider,
            method = "POST",
            path = "/conversations/$conversationId/messages/$messageId/delete",
            body = buildJsonObject {
                put("client_operation_id", Uuid.random().toString())
                put("base_revision", baseRevision)
            },
        )
        body["revision"]?.jsonPrimitive?.intOrNull
            ?: error("P14 delete response omitted revision")
    }

    private fun request(
        provider: ProviderSetting.OpenAI,
        method: String,
        path: String,
        sessionId: String? = null,
        body: kotlinx.serialization.json.JsonObject? = null,
    ): kotlinx.serialization.json.JsonObject {
        val builder = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Accept", "application/json")
        sessionId?.let { builder.header("X-Session-ID", it) }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(
                (body ?: buildJsonObject {}).toString()
                    .toRequestBody("application/json".toMediaType())
            )
            else -> error("Unsupported P14 control method: $method")
        }
        return client.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            check(response.isSuccessful) {
                "P14 control request failed: HTTP ${response.code} $text"
            }
            json.parseToJsonElement(text).jsonObject
        }
    }

    private fun kotlinx.serialization.json.JsonObject.requireString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("P14 response omitted $key")
}
