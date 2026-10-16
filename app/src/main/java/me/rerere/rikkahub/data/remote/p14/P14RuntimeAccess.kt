package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.uuid.Uuid

internal data class P14RuntimeConnection(val baseUrl: String, val bearerToken: String, val providerId: String)

internal class P14RuntimeCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("p14-rikkahub-runtime", Context.MODE_PRIVATE)
    fun deviceId(): String = prefs.getString("device_id", null) ?: Uuid.random().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    fun markActive(providerId:String, baseUrl:String) { prefs.edit().putString("active_provider",providerId).putString("active_base",baseUrl.trimEnd('/')).apply() }
    fun activeConnection():P14RuntimeConnection? {
        val pid=prefs.getString("active_provider",null)?:return null
        val base=prefs.getString("active_base",null)?:return null
        val token=load(pid,base)?:return null
        return P14RuntimeConnection(base,token,pid)
    }
    fun load(providerId: String, baseUrl: String): String? {
        val iv = prefs.getString("${providerId}_iv", null) ?: return null
        val data = prefs.getString("${providerId}_token", null) ?: return null
        if (prefs.getString("${providerId}_base", null) != baseUrl.trimEnd('/')) return null
        return runCatching {
            val cipher=Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
    }
    fun save(providerId: String, baseUrl: String, token: String) {
        val cipher=Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.ENCRYPT_MODE, key())
        val data=cipher.doFinal(token.toByteArray())
        prefs.edit().putString("${providerId}_base", baseUrl.trimEnd('/'))
            .putString("${providerId}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${providerId}_token", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    fun clear(providerId: String) { prefs.edit().remove("${providerId}_base").remove("${providerId}_iv").remove("${providerId}_token").apply() }
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(KEY_ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").run {
            init(android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    companion object { private const val KEY_ALIAS="rikkahub-p14-device-token-v1"; private const val TRANSFORMATION="AES/GCM/NoPadding" }
}

@Serializable private data class DeviceSession(val scope:String, @SerialName("device_id") val deviceId:String?=null)
@Serializable private data class DeviceIssued(@SerialName("device_id") val deviceId:String, @SerialName("device_token") val deviceToken:String, val active:Boolean)
@Serializable private data class IssueCommand(@SerialName("expected_revision") val expectedRevision:Int?=null)

internal class P14RuntimeAccess(
    context: Context,
    private val settingsStore: SettingsStore,
    private val http: OkHttpClient,
) {
    private val store=P14RuntimeCredentialStore(context.applicationContext)
    private val json=Json { ignoreUnknownKeys=true; explicitNulls=false }

    suspend fun prepare(provider: ProviderSetting.OpenAI): ProviderSetting.OpenAI {
        val pid=provider.id.toString(); val base=provider.baseUrl.trimEnd('/')
        store.load(pid, base)?.let { store.markActive(pid,base); return provider.copy(apiKey=it) }
        require(provider.apiKey.isNotBlank()) { "P14 Remote needs setup credential once on this device" }
        val setup=P14RuntimeConnection(base, provider.apiKey, pid)
        val session:DeviceSession=decode(get(setup,"device-session"))
        val token=when(session.scope) {
            "DEVICE" -> { require(session.deviceId==store.deviceId()) { "P14 device credential belongs to another installation" }; provider.apiKey }
            "OWNER_SETUP" -> {
                val issued:DeviceIssued=decode(post(setup,"devices/${store.deviceId()}/credential", json.encodeToString(IssueCommand())))
                require(issued.active && issued.deviceId==store.deviceId()); issued.deviceToken
            }
            else -> error("Unsupported P14 credential scope ${session.scope}")
        }
        store.save(pid,base,token)
        store.markActive(pid,base)
        // Never retain owner/setup or runtime bearer credentials in ordinary RikkaHub settings.
        if (provider.apiKey.isNotBlank()) settingsStore.update { settings ->
            settings.copy(providers=settings.providers.map { p ->
                if (p is ProviderSetting.OpenAI && p.id==provider.id) p.copy(apiKey="") else p
            })
        }
        return provider.copy(apiKey=token)
    }

    suspend fun connection(provider:ProviderSetting.OpenAI)=prepare(provider).let { P14RuntimeConnection(it.baseUrl.trimEnd('/'),it.apiKey,it.id.toString()) }

    suspend fun revoke(provider:ProviderSetting.OpenAI) {
        val runtime=connection(provider)
        post(runtime,"devices/${store.deviceId()}/revoke", "")
        store.clear(provider.id.toString())
    }

    fun deviceId()=store.deviceId()

    internal fun get(c:P14RuntimeConnection,path:String):String=execute(c,Request.Builder().url("${c.baseUrl}/${path.trimStart('/')}").get())
    internal fun post(c:P14RuntimeConnection,path:String,body:String):String=execute(c,Request.Builder().url("${c.baseUrl}/${path.trimStart('/')}").post(body.toRequestBody(JSON)))
    internal fun put(c:P14RuntimeConnection,path:String,body:String):String=execute(c,Request.Builder().url("${c.baseUrl}/${path.trimStart('/')}").put(body.toRequestBody(JSON)))
    private fun execute(c:P14RuntimeConnection,b:Request.Builder):String=http.newCall(b.header("Authorization","Bearer ${c.bearerToken}").header("Accept","application/json").build()).execute().use { r ->
        val text=r.body?.string().orEmpty(); if(!r.isSuccessful) throw P14RemoteHttpException(r.code,text.take(800)); text
    }
    private inline fun <reified T> decode(text:String):T=json.decodeFromString(text)
    companion object { private val JSON="application/json; charset=utf-8".toMediaType() }
}

internal class P14RemoteHttpException(val status:Int, detail:String): RuntimeException("P14 HTTP $status: $detail")
