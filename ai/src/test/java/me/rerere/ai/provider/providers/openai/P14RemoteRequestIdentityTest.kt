package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val latest = UIMessage.user("latest").copy(id = expected)
        val model = Model(modelId = "p14-hermes")
        val body = method.invoke(
            api,
            listOf(older, latest),
            TextGenerationParams(model = model),
            ProviderSetting.OpenAI(baseUrl = "https://p14.example/v1"),
            true,
        ) as JsonObject

        assertEquals(expected.toString(), body["p14_message_id"]?.jsonPrimitive?.content)
    }
}
