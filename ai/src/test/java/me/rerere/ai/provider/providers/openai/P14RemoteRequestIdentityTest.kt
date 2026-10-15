package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class P14RemoteRequestIdentityTest {
    @Test
    fun `p14 request carries latest local user message id`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true

        val expected = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val older = UIMessage.user("older")
        val target = Uuid.parse("22222222-2222-4222-8222-222222222222")
        val latest = UIMessage.user("latest").copy(
            id = expected,
            parts = listOf(
                me.rerere.ai.ui.UIMessagePart.Text(
                    "latest",
                    metadata = buildJsonObject {
                        put("p14_reply", buildJsonObject {
                            put("message_id", target.toString())
                            put("quote", buildJsonObject {
                                put("text", "quoted")
                                put("source_revision", 2)
                                put("start_char", 0)
                                put("end_char", 6)
                            })
                        })
                    }
                )
            ),
        )
        val model = Model(modelId = "p14-hermes")
        val body = method.invoke(
            api,
            listOf(older, latest),
            TextGenerationParams(model = model),
            ProviderSetting.OpenAI(baseUrl = "https://p14.example/v1"),
            true,
        ) as JsonObject

        assertEquals(expected.toString(), body["p14_message_id"]?.jsonPrimitive?.content)
        assertEquals(target.toString(), body["p14_reply_to_message_id"]?.jsonPrimitive?.content)
        assertEquals("quoted", body["p14_quote"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals("2", body["p14_quote"]?.jsonObject?.get("source_revision")?.jsonPrimitive?.content)
    }
}
