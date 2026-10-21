package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.activity.P14EntryActivity
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Last-on-B synthetic acceptance of ordinary settings and real limited-mode product UI. */
@RunWith(AndroidJUnit4::class)
class P14PolicySettingsUiInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val settings get()=GlobalContext.get().get<SettingsStore>()

    @Test fun ordinaryProviderModelDiscoveryUi():Unit = runBlocking {
        val runtime=guardRuntime()
        val original=provider(runtime.providerId)
        assertEquals("P14 Task4 synthetic",original.name)
        assertFalse("Fixture starts without manually entered P14 model",original.models.any { it.modelId=="p14-hermes" })
        val credentialHash=hash(runtime.bearerToken)
        val deviceId=P14RuntimeCredentialStore(context).deviceId()
        try {
            val intent=Intent(context,RouteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<RouteActivity>(intent).use {
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithContentDescription("Messages").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Messages").performClick()
                text(context.getString(R.string.settings)).performClick()
                val providersLabel=context.getString(R.string.setting_page_providers)
                compose.waitUntil(20_000) {
                    runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(providersLabel)) }.isSuccess
                }
                text(providersLabel).performClick()
                text(context.getString(R.string.setting_provider_page_search_providers))
                    .performTextReplacement(original.name)
                text(original.name).performClick()
                text(context.getString(R.string.setting_provider_page_models)).performClick()
                // Entering the production Models tab performs real model discovery.
                compose.onNodeWithContentDescription(context.getString(R.string.setting_provider_page_avaliable_models)).performClick()
                text(context.getString(R.string.setting_provider_page_avaliable_models)).assertIsDisplayed()
                text("p14-hermes").assertIsDisplayed()
                text(context.getString(R.string.setting_provider_page_select_all,1)).performClick()
                compose.waitUntil(15_000) { provider(runtime.providerId).models.any { it.modelId=="p14-hermes" } }
                Espresso.pressBack()
                compose.waitForIdle()
                text("p14-hermes").assertExists()
            }
            // Reopen the actual settings path's persisted result, not a manually created Model.
            assertEquals(1,provider(runtime.providerId).models.count { it.modelId=="p14-hermes" })
            assertCredentialUnchanged(runtime,credentialHash,deviceId,original.apiKey)
            evidence("ordinaryProviderModelDiscoveryUi PASS: real RouteActivity Settings/Providers/Models discovery and model-picker selection persisted p14-hermes; existing encrypted device identity unchanged; no manual model creation")
        } finally {
            settings.update { current -> current.copy(providers=current.providers.map {
                if(it.id.toString()==runtime.providerId) it.copyProvider(models=original.models) else it
            }) }
        }
    }

    @Test fun incompatibleManifestProductUi():Unit = runBlocking {
        val runtime=guardRuntime()
        val credentialHash=hash(runtime.bearerToken)
        val deviceId=P14RuntimeCredentialStore(context).deviceId()
        val marker=provider(runtime.providerId).apiKey
        val cache=context.getSharedPreferences("p14-manifest",Context.MODE_PRIVATE)
        val hadCache=cache.contains(BASE)
        val prior=cache.getString(BASE,null)
        val koin=GlobalContext.get()
        val original=koin.get<OkHttpClient>()
        val unavailable=AtomicBoolean(false)
        val hits=AtomicInteger(0)
        val controlled=original.newBuilder().addInterceptor { chain ->
            val request=chain.request()
            if(request.method=="GET" && request.url.toString()==BASE+"/remote-manifest?client_protocol=1") {
                hits.incrementAndGet()
                if(unavailable.get()) throw IOException("Synthetic exact Manifest transport unavailable")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("Synthetic incompatible Manifest").body(UPGRADE.toResponseBody("application/json".toMediaType())).build()
            } else chain.proceed(request)
        }.build()
        WorkManager.getInstance(context).cancelUniqueWork("p14-remote-sync").result.get(15,TimeUnit.SECONDS)
        try {
            // API verified against the project's pinned Koin4.2.2 source.
            koin.declare<OkHttpClient>(controlled,allowOverride=true)
            assertSame(controlled,koin.get<OkHttpClient>())
            assertLimitedProduct("REMOTE")
            assertTrue("Actual Activity must request the controlled Manifest",hits.get()>0)
            val firstHits=hits.get()
            unavailable.set(true)
            assertLimitedProduct("LAST_KNOWN_GOOD")
            assertTrue(hits.get()>firstHits)
            assertCredentialUnchanged(runtime,credentialHash,deviceId,marker)
            evidence("incompatibleManifestProductUi PASS: actual P14EntryActivity consumed exact controlled Manifest response and LKG, showed upgrade-required state and disabled composer/Send; synthetic wire branch, not a real Server upgrade deployment")
        } finally {
            koin.declare<OkHttpClient>(original,allowOverride=true)
            assertSame(original,koin.get<OkHttpClient>())
            val edit=cache.edit()
            if(hadCache) edit.putString(BASE,prior) else edit.remove(BASE)
            assertTrue(edit.commit())
            assertEquals(hadCache,cache.contains(BASE));assertEquals(prior,cache.getString(BASE,null))
        }
    }

    private fun assertLimitedProduct(source:String) {
        val conversation=requireNotNull(args.getString("p14MediaConversationId"))
        val intent=Intent(context,P14EntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(P14EntryActivity.EXTRA_CONVERSATION_ID,conversation)
        ActivityScenario.launch<P14EntryActivity>(intent).use {
            text("Client upgrade required").assertIsDisplayed()
            compose.onNodeWithTag("p14-entry-message-input").assertIsNotEnabled()
            compose.onNodeWithText("Send").assertIsNotEnabled()
            compose.waitUntil(20_000) {
                runCatching {
                    compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Manifest: UPGRADE_REQUIRED · "+source))
                }.isSuccess
            }
            compose.onNodeWithText("Manifest: UPGRADE_REQUIRED · "+source).assertIsDisplayed()
        }
    }

    private fun text(value:String):SemanticsNodeInteraction {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithText(value)
    }
    private fun provider(id:String)=settings.settingsFlow.value.providers.single { it.id.toString()==id } as ProviderSetting.OpenAI
    private fun guardRuntime():P14RuntimeConnection {
        require(args.getString("p14OwnerToken")=="task4-owner-synthetic-only")
        require(args.getString("p14BaseUrl")==BASE)
        require(context.packageName=="me.rerere.rikkahub.debug")
        return requireNotNull(P14RuntimeCredentialStore(context).activeConnection()).also { require(it.baseUrl==BASE) }
    }
    private fun assertCredentialUnchanged(runtime:P14RuntimeConnection,expectedHash:String,deviceId:String,marker:String) {
        val after=guardRuntime()
        assertEquals(runtime.providerId,after.providerId);assertEquals(BASE,after.baseUrl)
        assertEquals(expectedHash,hash(after.bearerToken))
        assertEquals(deviceId,P14RuntimeCredentialStore(context).deviceId())
        assertTrue(provider(runtime.providerId).apiKey==marker)
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun evidence(value:String) { android.util.Log.i("P14_TASK4",value) }
    private companion object {
        const val BASE="http://10.0.2.2:8014/v1"
        const val UPGRADE="""{"schema_version":"p14.remote-manifest/v1","manifest_version":1,"minimum_client_protocol":999,"maximum_client_protocol":999,"compatibility":"UPGRADE_REQUIRED","enabled_views":["chat","inbox"],"message_capabilities":["text"],"quick_action_types":[],"upload_limits":{"max_attachment_bytes":64,"max_attachments_per_message":1}}"""
    }
}
