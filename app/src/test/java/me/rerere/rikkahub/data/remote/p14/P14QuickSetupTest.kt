package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.AssistantRuntimeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P14QuickSetupTest {
    @Test
    fun setupCreatesCompleteRemoteRuntimeFromServerAndToken() {
        val result = Settings().withP14QuickSetup("http://100.84.55.11:18014/", "p14setup_test")
        val provider = result.settings.providers.filterIsInstance<ProviderSetting.OpenAI>().first { it.id == P14_PROVIDER_ID }
        val assistant = result.settings.assistants.first { it.id == P14_ASSISTANT_ID }
        assertEquals("http://100.84.55.11:18014/v1", provider.baseUrl)
        assertEquals("p14setup_test", provider.apiKey)
        assertEquals(P14_MODEL_NAME, provider.models.single().modelId)
        assertEquals(P14_MODEL_ID, assistant.chatModelId)
        assertEquals(AssistantRuntimeMode.REMOTE, assistant.runtimeMode)
        assertEquals(P14_ASSISTANT_ID, result.settings.assistantId)
    }

    @Test
    fun setupReplacesPriorP14ProviderInsteadOfDuplicatingIt() {
        val first = Settings().withP14QuickSetup("https://one.example/v1", "one").settings
        val second = first.withP14QuickSetup("https://two.example", "two").settings
        assertEquals(1, second.providers.count { p -> p is ProviderSetting.OpenAI && p.models.any { it.modelId == P14_MODEL_NAME } })
        assertEquals("https://two.example/v1", second.p14ConfiguredServerUrl() + "/v1")
        assertTrue(second.assistants.count { it.id == P14_ASSISTANT_ID } == 1)
    }
}
