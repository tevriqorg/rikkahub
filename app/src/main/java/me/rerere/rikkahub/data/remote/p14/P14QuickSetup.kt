package me.rerere.rikkahub.data.remote.p14

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantRuntimeMode
import kotlin.uuid.Uuid

internal val P14_PROVIDER_ID = Uuid.parse("14000001-0000-4000-8000-000000000001")
internal val P14_MODEL_ID = Uuid.parse("14000002-0000-4000-8000-000000000002")
internal val P14_ASSISTANT_ID = Uuid.parse("14000003-0000-4000-8000-000000000003")
internal const val P14_MODEL_NAME = "p14-hermes"
internal const val P14_DEFAULT_SERVER = "http://100.84.55.11:18014"

internal data class P14QuickSetupResult(
    val settings: Settings,
    val provider: ProviderSetting.OpenAI,
)

internal fun normalizeP14BaseUrl(raw: String): String {
    val base = raw.trim().trimEnd('/')
    require(base.startsWith("http://") || base.startsWith("https://")) {
        "Server URL 必须使用 http:// 或 https://"
    }
    return if (base.endsWith("/v1")) base else "$base/v1"
}

internal fun Settings.withP14QuickSetup(serverUrl: String, setupToken: String): P14QuickSetupResult {
    require(setupToken.isNotBlank()) { "P14 Server token 不能为空" }
    val baseUrl = normalizeP14BaseUrl(serverUrl)
    val model = Model(
        id = P14_MODEL_ID,
        modelId = P14_MODEL_NAME,
        displayName = "P14 Remote",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.TEXT),
    )
    val provider = ProviderSetting.OpenAI(
        id = P14_PROVIDER_ID,
        name = "P14 Remote",
        models = listOf(model),
        apiKey = setupToken.trim(),
        baseUrl = baseUrl,
        chatCompletionsPath = "/chat/completions",
        useResponseApi = false,
    )
    val assistant = Assistant(
        id = P14_ASSISTANT_ID,
        chatModelId = P14_MODEL_ID,
        name = "P14 Remote",
        runtimeMode = AssistantRuntimeMode.REMOTE,
    )
    return P14QuickSetupResult(
        settings = copy(
            providers = providers.filterNot { p ->
                p.id == P14_PROVIDER_ID || (p is ProviderSetting.OpenAI && p.models.any { it.modelId == P14_MODEL_NAME })
            } + provider,
            assistants = assistants.filterNot { it.id == P14_ASSISTANT_ID } + assistant,
            assistantId = P14_ASSISTANT_ID,
        ),
        provider = provider,
    )
}

internal fun Settings.p14ConfiguredServerUrl(): String? =
    providers.filterIsInstance<ProviderSetting.OpenAI>()
        .firstOrNull { it.id == P14_PROVIDER_ID || it.models.any { model -> model.modelId == P14_MODEL_NAME } }
        ?.baseUrl
        ?.removeSuffix("/v1")
